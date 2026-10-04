"""汇总器负例：旧报告、缺失组件、变化的业务字节不能变成完整 PASS。"""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
spec=importlib.util.spec_from_file_location('stage2_summary',Path(__file__).resolve().parents[1]/'summarize-stage2.py')
summary=importlib.util.module_from_spec(spec);spec.loader.exec_module(summary)


class SummaryTest(unittest.TestCase):
    def exercise(self, stale=False, changed=False):
        with tempfile.TemporaryDirectory() as work:
            root=Path(work);run=root/'run';run.mkdir();tx=root/'tx';tx.mkdir();rx=root/'rx';rx.mkdir()
            data={'schemaVersion':1,'runId':'synthetic-unit-test','startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),
                'checks':{'business_fixture':'PASS'},'businessGate':'PASS','milestones':[],'modelCases':[{'id':'correction','status':'PASS'}],
                'integrationBoundary':{'agentHitlToRealSubmission':'BLOCKED'},'expected':{'body':{'secret':'should-not-publish'}},
                'manifest':{'databaseNames':{'private':'omit'}}}
            (run/'report.json').write_text(json.dumps(data))
            # 模拟一份测试 XML，故意不填其余组件，证明单个通过不能盖住缺项。
            xml=tx/'TEST-example.xml';xml.write_text('<testsuite><testcase classname="example" name="one"/></testsuite>')
            if stale:os.utime(xml,(1,1))
            for side,path in [('sender',root/'target/cloud-customer-service-0.0.1-SNAPSHOT.jar'),
                              ('receiver',root/'after-sale-receiver/target/after-sale-receiver-0.0.1-SNAPSHOT.jar')]:
                path.parent.mkdir(parents=True,exist_ok=True)
                for target,value in [(run/(side+'.jar'),'original'),(path,'changed' if changed else 'original')]:
                    with zipfile.ZipFile(target,'w') as archive:archive.writestr('BOOT-INF/classes/Business.class',value)
            argv=['summary',str(run),'--root-reports',str(tx),'--receiver-reports',str(rx)]
            with patch.object(summary,'ROOT',root),patch.object(sys,'argv',argv),contextlib.redirect_stdout(io.StringIO()):
                code=summary.main()
            return code,json.loads((run/'public-report.json').read_text())

    def test_missing_component_is_not_run_and_private_fields_are_removed(self):
        code,data=self.exercise()
        self.assertEqual(data['engineeringGate'],'NOT_RUN');self.assertEqual(data['overallStage2'],'BLOCKED')
        self.assertNotIn('body',data['identity']);self.assertNotIn('databaseNames',data['manifest'])
        self.assertEqual(data['businessBinaryEquivalence']['sender']['status'],'PASS')

    def test_stale_xml_is_not_reused_as_green(self):
        _,data=self.exercise(stale=True)
        self.assertEqual(data['regression']['sender']['passed'],0)
        self.assertEqual(data['checks']['regression.sender_all_observed'],'NOT_RUN')

    def test_changed_business_jar_is_a_hard_failure(self):
        code,data=self.exercise(changed=True)
        self.assertEqual(code,1);self.assertEqual(data['engineeringGate'],'FAIL')
        self.assertEqual(data['overallStage2'],'FAIL')

if __name__=='__main__':unittest.main()
