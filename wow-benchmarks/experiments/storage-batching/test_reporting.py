"""Exercise both real runners with synthetic JVM outputs; no benchmark or database required."""
import gzip
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


class ReportingTest(unittest.TestCase):
    def test_missing_successes_produce_invalid_results_in_both_runners(self):
        for runner in ('run.py', 'run_mongo_v2.py'):
            for scenario in ('calibration', 'measurement', 'warmup', 'errors', 'empty'):
                with self.subTest(runner=runner, scenario=scenario), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    jar = root / 'fixture.jar'
                    jar.write_bytes(b'synthetic artifact')
                    output = root / 'output'
                    spec = importlib.util.spec_from_file_location('runner_under_test', HERE / runner)
                    module = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(module)

                    def fake_jvm(command, stdout, **kwargs):
                        if 'org.openjdk.jmh.Main' in command:
                            result = Path(command[command.index('-rff') + 1])
                            result.write_text(json.dumps([{
                                'primaryMetric': {'score': 100, 'rawData': [[100, 100, 100]]},
                                'secondaryMetrics': {'gc.alloc.rate.norm': {'score': 100}},
                            }]))
                            for i in range(1, 4):
                                stdout.write(f'# Warmup Iteration {i}: 100 ops/s\n')
                        elif 'batchharness.OpenLoop' in command:
                            prefix = Path(command[-1])
                            calibration = prefix.name.startswith('calibration-')
                            for phase in ('warm', 'measure'):
                                missing = scenario == 'calibration' or (not calibration and (
                                    phase == 'warm' if scenario == 'warmup' else True))
                                for i in range(3):
                                    with gzip.open(f'{prefix}-{phase}-{i}.csv.gz', 'wt') as stream:
                                        stream.write('planned_ns,submitted_ns,terminal_ns,result\n')
                                        if missing and scenario == 'empty':
                                            continue
                                        outcome = ('error' if scenario == 'errors' else 'not_sent') if missing else 'success'
                                        for planned in (0, 200000):
                                            submitted = 0 if outcome == 'not_sent' else planned + 1
                                            stream.write(f'{planned},{submitted},{planned + 1000000},{outcome}\n')
                        return subprocess.CompletedProcess(command, 0)

                    args = [runner, '--baseline', str(jar), '--candidate', str(jar), '--output', str(output)]
                    args += ['--run'] if runner == 'run.py' else ['--core-evidence', str(root)]
                    with patch.object(sys, 'argv', args), patch.object(module.subprocess, 'run', fake_jvm):
                        module.main()
                    results = json.loads((output / 'comparisons.json').read_text())
                    latency = [r for r in results if 'boundary' not in r]
                    if scenario == 'calibration':
                        self.assertIsNone(json.loads((output / 'selected-rate.json').read_text())['rate'])
                        self.assertEqual(latency, [])
                    else:
                        self.assertEqual(len(latency), 4)
                        self.assertTrue(all(not r['overall_valid'] for r in latency))
                        if scenario != 'warmup':
                            self.assertTrue(all(r['ratio'] is None and not r['noninferior'] for r in latency))
                    self.assertTrue((output / 'completed.json').exists())


if __name__ == '__main__':
    unittest.main()
