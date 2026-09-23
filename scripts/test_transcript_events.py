import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from transcript_events import normalize, parse, timestamp

HERE = Path(__file__).parent
FIXTURES = HERE / 'fixtures' / 'transcripts'
spec = importlib.util.spec_from_file_location('analyzer', HERE / 'analyze-latest-transcript.py')
analyzer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyzer)


def command(events, name, *args):
    with contextlib.redirect_stdout(io.StringIO()) as output:
        analyzer.COMMANDS[name](events, list(args))
    return output.getvalue()


class TranscriptTests(unittest.TestCase):
    def test_shared_commands(self):
        for harness in ('claude', 'opencode', 'pi', 'codex'):
            with self.subTest(harness=harness):
                events = analyzer.load(FIXTURES / f'{harness}.jsonl')
                summary = next(e for e in events if e['type'] == 'result')
                self.assertEqual(summary['num_turns'], 2)
                self.assertEqual(summary['usage']['input_tokens'], 32)
                self.assertEqual(summary['usage']['output_tokens'], 18)
                self.assertEqual(summary['usage']['cache_read_input_tokens'], 8)
                self.assertEqual(summary['result'], 'PHASE_VALIDATION:pass. Unique final response.')
                self.assertEqual(command(events, 'search', 'Unique final').count('Unique final'), 1)
                self.assertEqual(command(events, 'thinking', 'asymmetric').count('asymmetric'), 1)
                self.assertEqual(command(events, 'thinking-blocks').count('=== LINE'), 1)
                self.assertEqual(command(events, 'test-runs').count('7 assertions'), 1)
                self.assertEqual(command(events, 'tool-results', '7 assertions').count('7 assertions'), 1)
                self.assertIn('BASH: clojure -X:test', command(events, 'timeline'))
                if harness != 'codex':
                    self.assertAlmostEqual(summary['total_cost_usd'], 0.02)
                    self.assertEqual(summary['duration_ms'], 12000)
                    self.assertEqual(summary['usage']['cache_creation_input_tokens'], 3)
                    self.assertIn('references/paths.md', command(events, 'reads'))
                    self.assertIn('new old\n', command(events, 'module'))
                    self.assertNotIn('WRONG', command(events, 'module'))
                    self.assertIn('new old\n', command(events, 'final-write', 'module.clj'))
                    self.assertIn('old old', command(events, 'module', 'all'))
                    self.assertIn('module.clj', command(events, 'writes'))
                    self.assertIn('new: new', command(events, 'edits'))
                    self.assertIn('CONFUSION: inspect', command(events, 'confusions'))
                    self.assertIn('CONFUSION: inspect', command(events, 'reasoning'))
                    self.assertIn('edit rejected', command(events, 'errors'))
                else:
                    self.assertIsNone(summary['total_cost_usd'])
                    self.assertIsNone(summary['duration_ms'])
                    self.assertIn('no module.clj writes', command(events, 'module'))
                self.assertEqual(normalize(events), events, 'canonical input must be idempotent')

    def test_failure_is_not_final_response(self):
        for harness, error in (
            ('opencode', {'type': 'error', 'error': {'message': 'provider failed'}}),
            ('pi', {'type': 'message_end', 'message': {'role': 'assistant', 'content': [],
                    'stopReason': 'error', 'errorMessage': 'provider failed'}}),
            ('codex', {'type': 'turn.failed', 'error': {'message': 'provider failed'}}),
        ):
            with self.subTest(harness=harness):
                raw = parse((FIXTURES / f'{harness}.jsonl').read_text())
                events = normalize(raw + [error])
                summary = events[-1]
                self.assertEqual(summary['status'], 'error')
                self.assertNotIn('provider failed', summary['result'])
                self.assertIn('provider failed', command(events, 'summary'))
                self.assertIn('provider failed', command(events, 'errors'))

    def test_opencode_privacy_rejection_diagnostic(self):
        error = {'name': 'APIError', 'data': {
            'message': 'Paid model training violation (account settings)',
            'statusCode': 404, 'responseHeaders': {'set-cookie': 'private-cookie'}}}
        events = normalize([{'type': 'error', 'error': error}])
        for name in ('summary', 'errors'):
            output = command(events, name)
            self.assertIn('Paid model training violation', output)
            self.assertNotIn('private-cookie', output)
        self.assertIn('private-cookie', command(events, 'events'))

    def test_partial_and_timeout(self):
        events = normalize([{'type': 'session', 'timestamp': '2026-09-16T00:00:00Z'},
                            {'type': 'message_update', 'assistantMessageEvent': {'type': 'text_delta', 'delta': 'partial'}},
                            {'type': 'run_metadata', 'exit': 1, 'timed_out': True, 'duration_s': 9}])
        self.assertEqual(events[-1]['status'], 'timeout')
        self.assertEqual(events[-1]['duration_ms'], 9000)
        self.assertIsNone(events[-1]['num_turns'])
        self.assertIsNone(events[-1]['total_cost_usd'])
        self.assertEqual(events[1]['source_event']['assistantMessageEvent']['delta'], 'partial')
        self.assertEqual(normalize([{'type': 'agent_start'}])[-1]['status'], 'incomplete')
        self.assertIn('partial', command(events, 'events'))
        for harness, start in (('opencode', 'step_start'), ('pi', 'agent_start'), ('codex', 'turn.started')):
            raw = parse((FIXTURES / f'{harness}.jsonl').read_text())
            self.assertEqual(normalize(raw + [{'type': start}])[-1]['status'], 'incomplete')
        failed = normalize([{'type': 'run_metadata', 'exit': 127, 'stderr': 'CLI not found'}])
        self.assertEqual(failed[-1]['errors'], ['CLI not found'])

    def test_opencode_running_then_completed_and_repeated_step(self):
        running = {'type': 'tool_use', 'part': {'id': 'p', 'tool': 'bash', 'callID': 'c',
                   'state': {'status': 'running', 'input': {'command': 'echo hello'}}}}
        completed = {'type': 'tool_use', 'part': {**running['part'], 'state': {
                     **running['part']['state'], 'status': 'completed', 'output': 'hello'}}}
        step = {'type': 'step_finish', 'part': {'id': 's', 'reason': 'stop', 'cost': 0.01,
                                               'tokens': {'input': 13}}}
        events = normalize([running, completed, completed, step, step])
        self.assertEqual(command(events, 'timeline').count('BASH:'), 1)
        self.assertEqual(command(events, 'tool-results', 'hello').count('hello'), 1)
        self.assertEqual(events[-1]['usage']['input_tokens'], 13)
        self.assertEqual(events[-1]['num_turns'], 1)

    def test_opencode_whitespace_edit_replay(self):
        original = '  first\n    second\nend\n'
        replacement = r'new\1'
        for content, old, replace_all, status, expected in (
            (original, ' first\n second', False, 'completed', replacement + '\nend\n'),
            (original, ' first\n second\n', False, 'completed', replacement + 'end\n'),
            (original * 2, ' first\n second', False, 'completed', original * 2),
            (original * 2, ' first\n second', True, 'completed', (replacement + '\nend\n') * 2),
            (original, ' first\n second', False, 'error', original),
            (original, ' first\n missing', False, 'completed', original),
        ):
            with self.subTest(content=content, old=old, replace_all=replace_all, status=status):
                raw = [
                    {'type': 'tool_use', 'part': {'tool': 'write', 'callID': 'w', 'state': {
                        'status': 'completed', 'input': {'filePath': 'module.clj', 'content': content}}}},
                    {'type': 'tool_use', 'part': {'tool': 'edit', 'callID': 'e', 'state': {
                        'status': status, 'input': {'filePath': 'module.clj', 'oldString': old,
                                                  'newString': replacement, 'replaceAll': replace_all}}}},
                ]
                self.assertEqual(analyzer._final_file_content(normalize(raw), 'module.clj')[2], expected)
        # Other harnesses retain exact-match semantics.
        events = [{'type': 'assistant', 'message': {'content': [
            {'type': 'tool_use', 'name': 'Write', 'input': {'file_path': 'module.clj', 'content': original}},
            {'type': 'tool_use', 'name': 'Edit', 'input': {'file_path': 'module.clj',
             'old_string': ' first\n second', 'new_string': replacement}},
        ]}}]
        self.assertEqual(analyzer._final_file_content(events, 'module.clj')[2], original)

    def test_event_index_selection(self):
        self.assertEqual(command([{'type': 'first'}, {'type': 'second'}], 'events', '1'),
                         '{"type": "second"}\n')

    def test_missing_and_zero_cost(self):
        def finish(ident, cost):
            return {'type': 'step_finish', 'part': {'id': ident, 'reason': 'stop',
                                                   'tokens': {'input': 1}, **cost}}
        self.assertEqual(normalize([finish('a', {'cost': 0})])[-1]['total_cost_usd'], 0)
        self.assertIsNone(normalize([finish('a', {'cost': 1}), finish('b', {})])[-1]['total_cost_usd'])

    def test_malformed_and_unknown(self):
        events = normalize(parse('not json\n[]\n\n{"type":"future_event","payload":42}\n'))
        self.assertEqual(events[0]['text'], 'not json')
        self.assertEqual(events[1]['source_line'], 2)
        self.assertEqual(events[2]['payload'], 42)
        self.assertIn('not json', command(events, 'errors'))

    def test_timestamps_and_overview(self):
        self.assertEqual(timestamp(1000000), '1970-01-01T00:16:40.000+00:00')
        self.assertEqual(timestamp('1970-01-01T01:16:40+01:00'), timestamp(1000000))
        with patch.object(analyzer, 'LATEST_TRANSCRIPTS_DIR', str(FIXTURES)):
            output = command(None, 'run-overview')
        self.assertIn('pi.jsonl', output)
        self.assertIn('opencode.jsonl', output)
        self.assertIn('sum of phase durations: 36s', output)
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'empty.jsonl').write_text('{}\n')
            with patch.object(analyzer, 'LATEST_TRANSCRIPTS_DIR', directory):
                self.assertIn('no timestamps', command(None, 'run-overview'))

    def test_pi_session_history(self):
        raw = parse((FIXTURES / 'pi.jsonl').read_text())
        session = [raw[0]] + [{**e, 'type': 'message'} for e in raw if e['type'] == 'message_end']
        events = normalize(session)
        self.assertEqual(events[-1]['usage']['input_tokens'], 32)
        self.assertEqual(command(events, 'reads').count('paths.md'), 1)

    def test_tool_result_matching_not_proximity(self):
        raw = [{'type': 'tool_execution_start', 'toolName': 'bash', 'toolCallId': 'test',
                'args': {'command': 'clojure -X:test'}}]
        raw += [{'type': 'message_update'}] * 10
        raw += [{'type': 'tool_execution_end', 'toolCallId': 'other', 'result': {
                    'content': [{'type': 'text', 'text': '99 assertions unrelated'}]}},
                {'type': 'tool_execution_end', 'toolCallId': 'test', 'result': {
                    'content': [{'type': 'text', 'text': '7 assertions expected'}]}}]
        output = command(normalize(raw), 'test-runs')
        self.assertIn('7 assertions expected', output)
        self.assertNotIn('99 assertions', output)

    def test_test_run_entry_points(self):
        for cmd, expected in (
            ('clojure -X:test', True),
            ('cd challenges/example && clj -M:test', True),
            ('cd /work/challenges/example && timeout 300 clojure -X:test', True),
            ('timeout 1.5m clj -M -e "(clojure.test/run-tests)"', True),
            ('clojure -Sdeps \'{:aliases {:mytest {:extra-paths ["test"]}}}\' '
             '-M:mytest -e "(clojure.test/run-tests \'example-test)"', True),
            ('clojure -M -e "(t/run-tests)"', True),
            ('grep -n "clojure -X:test" scripts/run_challenges.bb', False),
            ('clojure -M -e "(println :test)"', False),
        ):
            with self.subTest(command=cmd):
                events = normalize([{'type': 'tool_execution_start', 'toolName': 'bash',
                                     'toolCallId': 'run', 'args': {'command': cmd}},
                                    {'type': 'tool_execution_end', 'toolCallId': 'run',
                                     'result': {'content': [{'type': 'text', 'text':
                                                'Ran 3 tests containing 17 assertions.'}]}}])
                self.assertEqual('17 assertions' in command(events, 'test-runs'), expected)

    def test_multiblock_final_response_and_codex_error_string(self):
        events = normalize([{'type': 'message_end', 'message': {'role': 'assistant',
                            'content': [{'type': 'text', 'text': 'first'}, {'type': 'text', 'text': 'second'}],
                            'stopReason': 'stop'}}])
        self.assertEqual(events[-1]['result'], 'first\nsecond')
        events = normalize([{'type': 'thread.started'}, {'type': 'error', 'message': 'provider failed'}])
        self.assertEqual(events[-1]['errors'], ['provider failed'])

    def test_cli_file_selection(self):
        r = subprocess.run(['python3', str(HERE / 'analyze-latest-transcript.py'), '--file',
                            str(FIXTURES / 'pi.jsonl'), 'summary'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('Turns: 2', r.stdout)
        self.assertIn('Cost: $0.020000', r.stdout)


if __name__ == '__main__':
    unittest.main()
