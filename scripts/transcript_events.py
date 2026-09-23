"""Native JSONL adapters for the shared Claude-compatible analysis stream.

Only final message snapshots contribute content/usage. Lifecycle events remain
auditable under source_event; they are never searched as duplicate messages.
Run as a filter to normalize stdin for the Babashka runner.
"""
import json
import sys
from datetime import datetime, timezone


def timestamp(value):
    if value is None:
        return None
    try:
        dt = (datetime.fromtimestamp(value / 1000, timezone.utc)
              if isinstance(value, (int, float)) else
              datetime.fromisoformat(value.replace('Z', '+00:00')))
        return dt.replace(tzinfo=dt.tzinfo or timezone.utc).astimezone(timezone.utc).isoformat(timespec='milliseconds')
    except (ValueError, TypeError, OverflowError, OSError):
        return None


def parse(text):
    events = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
            if not isinstance(event, dict):
                raise ValueError('expected an object')
        except ValueError:
            event = {'type': 'raw_output', 'text': line, 'source_line': number}
        events.append(event)
    return events


def detect(events):
    for e in events:
        if 'source_event' in e:
            continue
        t = e.get('type') or ''
        if t in ('message_end', 'message_start', 'message_update', 'agent_start', 'agent_end',
                 'tool_execution_start', 'tool_execution_end', 'turn_start', 'turn_end', 'session', 'message'):
            return 'pi'
        if t in ('step_start', 'step_finish') or ('part' in e and t in ('text', 'tool_use', 'reasoning')):
            return 'opencode'
        if t.startswith(('thread.', 'turn.', 'item.')):
            return 'codex'
    return 'claude'


def text_content(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return '\n'.join(b.get('text', '') for b in content if isinstance(b, dict) and b.get('type') == 'text')
    return json.dumps(content) if content is not None else ''


def tool(name, args, ident):
    names = {'read': 'Read', 'write': 'Write', 'edit': 'Edit', 'bash': 'Bash',
             'glob': 'Glob', 'grep': 'Grep', 'skill': 'Skill', 'todowrite': 'TodoWrite'}
    fields = {'filePath': 'file_path', 'path': 'file_path', 'oldString': 'old_string',
              'newString': 'new_string', 'oldText': 'old_string', 'newText': 'new_string',
              'replaceAll': 'replace_all'}
    args = dict(args or {})
    for source, target in fields.items():
        if source in args and target not in args:
            args[target] = args.pop(source)
    if name == 'skill' and 'name' in args:
        args['skill'] = args.pop('name')
    return {'type': 'tool_use', 'name': names.get(name, name), 'input': args, 'id': ident}


def normalize(events):
    harness = detect(events)
    out = []
    calls, results, parts = set(), set(), set()

    def emit(e, blocks=None, role='assistant', **fields):
        message = e.get('message')
        ts = timestamp(e.get('timestamp') if e.get('timestamp') is not None else
                       message.get('timestamp') if isinstance(message, dict) else None)
        row = {'type': 'source_event', 'timestamp': ts, 'source_event': e, **fields}
        if blocks:
            row.update(type=role, message={'role': role, 'content': blocks})
        out.append(row)

    def call(e, name, args, ident):
        if ident is None or ident not in calls:
            emit(e, [tool(name, args, ident)])
            calls.add(ident)
        else:
            emit(e)

    def result(e, ident, content, failed=False):
        if ident is None or ident not in results:
            emit(e, [{'type': 'tool_result', 'tool_use_id': ident,
                      'content': text_content(content), 'is_error': bool(failed)}], 'user')
            results.add(ident)
        else:
            emit(e)

    def usage(e, u, cost=None):
        emit(e, type='usage', usage=u, total_cost_usd=cost)

    def pi_message(e, m):
        role = m.get('role')
        if role == 'toolResult':
            result(e, m.get('toolCallId'), m.get('content'), m.get('isError'))
            return
        blocks = m.get('content', [])
        if isinstance(blocks, str):
            blocks = [{'type': 'text', 'text': blocks}]
        content = []
        for b in blocks:
            if b.get('type') == 'toolCall':
                ident = b.get('id')
                if ident is None or ident not in calls:
                    content.append(tool(b.get('name'), b.get('arguments'), ident))
                    calls.add(ident)
            else:
                content.append(b)
        emit(e, content, role or 'assistant')
        if role == 'assistant':
            u = m.get('usage')
            if u is not None:
                usage(e, {'input_tokens': u.get('input', 0), 'output_tokens': u.get('output', 0),
                          'cache_read_input_tokens': u.get('cacheRead', 0),
                          'cache_creation_input_tokens': u.get('cacheWrite', 0)},
                      (u.get('cost') or {}).get('total'))
            emit(e, type='turn_end', stop_reason=m.get('stopReason'))
            if m.get('stopReason') in ('error', 'aborted') or m.get('errorMessage'):
                emit(e, type='error', error=m.get('errorMessage') or m['stopReason'])

    for e in events:
        t = e.get('type')
        # Already canonical (including PR #1 transcripts) is idempotent.
        if harness == 'claude' or t in ('run_metadata', 'raw_output'):
            row = dict(e)
            if e.get('timestamp') is not None:
                row['timestamp'] = timestamp(e['timestamp'])
            out.append(row)
            continue
        if harness == 'opencode':
            p = e.get('part') or {}
            key = (t, p.get('id'))
            if t != 'tool_use' and p.get('id') and key in parts:
                emit(e)
                continue
            if p.get('id'):
                parts.add(key)
            if t in ('text', 'reasoning'):
                emit(e, [{'type': 'text', 'text': p.get('text', '')} if t == 'text' else
                         {'type': 'thinking', 'thinking': p.get('text', '')}])
            elif t == 'tool_use':
                state = p.get('state') or {}
                ident = p.get('callID') or p.get('id')
                args = state.get('input') or {}
                if p.get('tool') == 'edit':
                    args = {**args, 'match_line_whitespace': True}
                call(e, p.get('tool'), args, ident)
                if state.get('status') in ('completed', 'error'):
                    result(e, ident, state.get('output', state.get('error', '')), state.get('status') == 'error')
            elif t == 'step_finish':
                u = p.get('tokens')
                if u is not None:
                    cache = u.get('cache') or {}
                    usage(e, {'input_tokens': u.get('input', 0), 'output_tokens': u.get('output', 0),
                              'cache_read_input_tokens': cache.get('read', 0),
                              'cache_creation_input_tokens': cache.get('write', 0)}, p.get('cost'))
                emit(e, type='turn_end', stop_reason=p.get('reason'))
            elif t == 'step_start':
                emit(e, type='turn_start')
            elif t == 'error':
                emit(e, type='error', error=e.get('error'))
            else:
                emit(e)
        elif harness == 'pi':
            # message_start/update, turn_end, and agent_end repeat snapshots.
            # Session JSONL uses `message`; streaming JSONL uses `message_end`.
            if t in ('message_end', 'message'):
                pi_message(e, e.get('message') or {})
            elif t == 'tool_execution_start':
                call(e, e.get('toolName'), e.get('args'), e.get('toolCallId'))
            elif t == 'tool_execution_end':
                result(e, e.get('toolCallId'), (e.get('result') or {}).get('content'), e.get('isError'))
            elif t == 'agent_end':
                emit(e, type='session_end')
            elif t in ('agent_start', 'turn_start'):
                emit(e, type='turn_start')
            elif t == 'error':
                emit(e, type='error', error=e.get('error'))
            else:
                emit(e)
        else:  # Codex exec --json
            item = e.get('item') or {}
            kind, ident = item.get('type'), item.get('id')
            if t in ('item.started', 'item.completed') and kind == 'command_execution':
                call(e, 'bash', {'command': item.get('command', '')}, ident)
                if t == 'item.completed':
                    result(e, ident, item.get('aggregated_output'),
                           item.get('status') == 'failed' or bool(item.get('exit_code')))
            elif t == 'item.completed' and kind in ('agent_message', 'reasoning'):
                emit(e, [{'type': 'text', 'text': item.get('text', '')} if kind == 'agent_message' else
                         {'type': 'thinking', 'thinking': text_content(item.get('text', item.get('summary', '')))}])
            elif t == 'item.completed' and kind == 'file_change':
                call(e, 'FileChange', {'changes': item.get('changes'), 'status': item.get('status')}, ident)
            elif t == 'turn.completed':
                u = e.get('usage')
                if u is not None:
                    usage(e, {'input_tokens': u.get('input_tokens', 0),
                              'output_tokens': u.get('output_tokens', 0),
                              'cache_read_input_tokens': u.get('cached_input_tokens', 0),
                              'cache_creation_input_tokens': 0})
                emit(e, type='turn_end', stop_reason='turn.completed')
            elif t in ('turn.failed', 'error'):
                emit(e, type='error', error=e.get('error', e.get('message')))
            elif t == 'turn.started':
                emit(e, type='turn_start')
            else:
                emit(e)
    if not any(e.get('type') == 'result' for e in out):
        out.append(summarize(out, harness))
    return out


def summarize(events, harness):
    usages = [e for e in events if e.get('type') == 'usage']
    turns = [e for e in events if e.get('type') == 'turn_end']
    times = [e['timestamp'] for e in events if e.get('timestamp')]
    metadata = next((e for e in reversed(events) if e.get('type') == 'run_metadata' and 'exit' in e), {})
    errors = [e.get('error') for e in events if e.get('type') == 'error']
    stop = turns[-1].get('stop_reason') if turns else None
    boundary = next((e['type'] for e in reversed(events)
                     if e.get('type') in ('turn_start', 'turn_end', 'session_end')), None)
    complete = (boundary == 'session_end' or
                (boundary == 'turn_end' and stop in ('stop', 'end_turn', 'turn.completed')))
    status = 'completed' if complete else 'incomplete'
    if errors or metadata.get('exit', 0) != 0:
        status = 'error'
        if metadata.get('stderr'):
            errors.append(metadata['stderr'])
    if metadata.get('timed_out'):
        status, stop = 'timeout', 'timeout'
    totals = {}
    for e in usages:
        for k, v in e['usage'].items():
            totals[k] = totals.get(k, 0) + v
    costs = [e.get('total_cost_usd') for e in usages]
    duration = metadata.get('duration_s')
    if duration is not None:
        duration *= 1000
    elif len(times) >= 2:
        duration = (datetime.fromisoformat(max(times)) - datetime.fromisoformat(min(times))).total_seconds() * 1000
    texts = [text_content(e['message']['content']) for e in events
             if e.get('type') == 'assistant' and any(b.get('type') == 'text' for b in e['message']['content'])]
    return {'type': 'result', 'source': harness, 'duration_ms': duration,
            'total_cost_usd': sum(costs) if costs and all(c is not None for c in costs) else None,
            'num_turns': len(turns) if turns else None, 'stop_reason': stop,
            'status': status, 'is_error': status in ('error', 'timeout'),
            'errors': errors, 'result': texts[-1] if texts else None, 'usage': totals}


if __name__ == '__main__':
    for event in normalize(parse(sys.stdin.read())):
        print(json.dumps(event))
