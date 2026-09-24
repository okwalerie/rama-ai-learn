#!/usr/bin/env python3
"""Inventory a single challenge run without reading or exporting file contents."""

import argparse
import json
from pathlib import Path
import re


def inventory(workspace, challenge, prefix, proxy_logs=()):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]*', challenge):
        raise ValueError('invalid challenge name')
    if not re.fullmatch(r'\d{4}-\d\d-\d\d-\d{6}', prefix):
        raise ValueError('prefix must be the shared YYYY-MM-DD-HHMMSS run timestamp')
    workspace = Path(workspace).resolve(strict=True)
    entries = []

    def add(path, kind):
        # A symlink, including a symlinked parent, must never point inventory
        # outside the selected workspace. Do not follow even internal links.
        if not path.is_file() or any(p.is_symlink() for p in (path, *path.parents)
                                     if p != workspace and p != workspace.parent):
            return
        if not path.is_relative_to(workspace):
            return
        entries.append({'path': str(path.relative_to(workspace)), 'kind': kind,
                        'bytes': path.stat().st_size})

    phase = re.compile(rf'^{re.escape(prefix)}-[a-zA-Z0-9_./-]+-{re.escape(challenge)}'
                       r'(?:-[a-zA-Z0-9_-]+)?-phase(?:\d+|[a-z-]+)'
                       r'(?:-attempt\d+)?(?:-retry\d+)?\.jsonl$')
    transcripts = workspace / 'transcripts'
    if transcripts.is_dir() and not transcripts.is_symlink():
        for path in transcripts.iterdir():
            if phase.fullmatch(path.name):
                add(path, 'native-phase-jsonl')

    impl = workspace / 'repo' / 'implementations' / challenge
    if impl.is_dir() and not impl.is_symlink():
        for name in ('REASONING.md', 'PLAN.md', 'PLAN_VALIDATION.md',
                     'IMPLICIT_SPEC.md', 'IMPLEMENTATION_VALIDATION.md',
                     'TEST_VALIDATION.md', 'FULL_SPEC_REVIEW.md', 'DECOMPOSITION.json'):
            add(impl / name, 'solver-artifact')
        for path in (impl / 'src').glob('*/module.clj'):
            add(path, 'implementation')
        for path in (impl / 'src').glob('*/solution.clj'):
            add(path, 'implementation')
        for path in (impl / 'attempts').glob('attempt*.clj'):
            add(path, 'implementation-snapshot')

    reports = workspace / 'reports'
    if reports.is_dir() and not reports.is_symlink():
        # Report timestamps are generated separately from phase run timestamps.
        # These are candidates, never asserted to belong to the selected run.
        for path in reports.glob('*.md'):
            add(path, 'runner-report-candidate')
    for name in proxy_logs:
        candidate = Path(name)
        if candidate.is_absolute() or '..' in candidate.parts or candidate.suffix != '.log':
            raise ValueError('proxy log must be a workspace-relative .log path')
        path = workspace / name
        add(path, 'proxy-log-candidate')

    return {'challenge': challenge, 'run_timestamp': prefix,
            'entries': sorted(entries, key=lambda e: e['path']),
            'warning': 'Metadata only. Presence does not prove completeness, safety, or provenance.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('workspace', help='Parent of repo/, transcripts/, reports/')
    parser.add_argument('challenge')
    parser.add_argument('run_prefix', help='Shared YYYY-MM-DD-HHMMSS timestamp')
    parser.add_argument('--proxy-log', action='append', default=[],
                        help='Workspace-relative path; metadata only')
    args = parser.parse_args()
    print(json.dumps(inventory(args.workspace, args.challenge,
                               args.run_prefix, args.proxy_log), indent=2))


if __name__ == '__main__':
    main()
