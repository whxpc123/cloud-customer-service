"""评分器不能用一个正确片段、空结果或缺失测试制造通过结论。"""
import sys
from pathlib import Path
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from acceptance.report import coverage, gate, junit, rag_scores, percentiles, live_contracts

class AcceptanceReportTest(unittest.TestCase):
    def test_partial_groups_are_not_complete(self):
        r=coverage([['a@1#1','a2@1#1'],['b@1#1'],['c@1#1']], [{'sourceId':x,'sourceVersion':'1','chunkIndex':1} for x in ['a','a','b']])
        self.assertEqual(r['covered'],2);self.assertFalse(r['complete']);self.assertEqual(r['status'],'FAIL')
    def test_equivalent_evidence_can_cover_group(self):
        self.assertTrue(coverage([['a@1#1','b@1#1']], [{'sourceId':'b','sourceVersion':'1','chunkIndex':1}])['complete'])
    def test_missing_stage_and_no_ground_truth_are_not_pass(self):
        self.assertEqual(coverage([['a']],None)['status'],'NOT_EXECUTED');self.assertIsNone(coverage([],[])['rate'])
        self.assertEqual(coverage([['a']],[])['status'],'FAIL')
    def test_invalid_group_rejected(self):
        with self.assertRaises(ValueError):coverage([[]],[])
    def test_missing_skipped_and_failed_tests_block_gate(self):
        self.assertEqual(gate({},['X']),'NOT_EXECUTED')
        self.assertEqual(gate({'X#a':'PASS','X#b':'NOT_EXECUTED'},['X']),'NOT_EXECUTED')
        self.assertEqual(gate({'X#a':'PASS','Y#a':'FAIL'},['X','Y']),'FAIL')
    def test_junit_failure_skipped_and_success(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d,'TEST-x.xml').write_text('<testsuite><testcase classname="X" name="a"/><testcase classname="X" name="b"><skipped/></testcase><testcase classname="X" name="c"><error/></testcase></testsuite>')
            self.assertEqual(junit(d),{'X#a':'PASS','X#b':'NOT_EXECUTED','X#c':'FAIL'})
    def test_missing_live_result_does_not_implicitly_score_zero(self):
        r=rag_scores({'cases':[{'caseId':'x','acceptableEvidenceGroups':[['a']]}]}, {})
        self.assertIsNone(r['groupCoverage']);self.assertEqual(r['cases'][0]['requestStatus'],'NOT_EXECUTED')
    def test_small_sample_not_capacity_claim(self):
        self.assertEqual(percentiles([{'elapsedMs':10},{'elapsedMs':30}])['p95Ms'],30)
        self.assertEqual(percentiles([])['capacityConclusion'],'NOT_EVALUATED');self.assertIsNone(percentiles([])['p50Ms'])

    def test_recall_and_final_prompt_are_scored_separately(self):
        doc={'sourceId':'a','sourceVersion':'1','chunkIndex':1}
        dataset={'cases':[{'caseId':'x','acceptableEvidenceGroups':[['a@1#1']]}]}
        live={'rag':[{'caseId':'x','response':{'expansion':{'retrievals':[{'documents':[doc]}]},'reranking':{'before':[doc],'after':[doc]},'references':[]}}]}
        stages=rag_scores(dataset,live)['cases'][0]['stages']
        self.assertTrue(stages['vectorBranches']['complete']);self.assertFalse(stages['finalPrompt']['complete'])
    def test_wrong_scope_is_a_hard_failure(self):
        live={'knowledgeManifest':[{'documentId':'x','metadata':{'tenantId':'other','status':'PUBLISHED','sourceVersion':'1'}}], 'rag':[{'response':{'references':[{'documentId':'x','sourceVersion':'1'}]}}]}
        self.assertEqual(live_contracts(live)['evidenceScope'],'FAIL')
    def test_present_but_empty_live_response_is_failure(self):
        self.assertEqual(live_contracts({'formalHttp':[{'question':'你好','response':{}}]})['greeting'],'FAIL')

if __name__=='__main__':unittest.main()
