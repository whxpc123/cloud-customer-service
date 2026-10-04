"""对验收器本身做负例：存在一行不等于正确，缺失数据不能得到 PASS。"""
import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stage2.evidence import verify, verdict, PASS, FAIL, BLOCKED, NOT_RUN


def fixture():
    # 预期先独立构造，再构造持久化观察；每项变异仅改观察数据。
    expected={'task_id':'task','tenant_id':'tenant-yunshan','user_id':1001,'order_no':'A10001',
        'operation_id':'op','application_id':'app','event_id':'event','producer_id':'producer',
        'body':{'userStatement':{'userDescription':'右侧按钮按不动','requestedHandling':'先审核'}}}
    payload={'eventId':'event','applicationId':'app','operationId':'op','tenantId':'tenant-yunshan',
        'draftVersion':2,'orderNo':'A10001','userStatement':copy.deepcopy(expected['body']['userStatement'])}
    sender={'tasks':[{'task_id':'task','tenant_id':'tenant-yunshan','user_id':1001,'order_no':'A10001','status':'CLOSED'}],
        'operations':[{'task_id':'task','operation_id':'op','draft_version':2,'status':'SUCCEEDED','decided_by':1001,'delivery_profile':'AFTER_SALE_V1'}],
        'applications':[{'application_id':'app','operation_id':'op','task_id':'task','draft_version':2,
            'tenant_id':'tenant-yunshan','user_id':1001,'order_no':'A10001','status':'PENDING_REVIEW','body_snapshot':copy.deepcopy(expected['body'])}],
        'outbox':[{'event_id':'event','application_id':'app','operation_id':'op','payload':payload,'attempt_count':8,
            'status':'REVIEW','remote_application_id':None,'last_error_code':'HTTP_503'}],
        'revisions':[{'draft_version':2,'body_json':copy.deepcopy(expected['body'])}],
        'confirmations':[{'task_id':'task','draft_version':2,'confirmed_by':1001,'scope':'DRAFT_CONTENT_ONLY'}], 'checks':[]}
    receiver={'inbox':[{'event_id':'event','producer_id':'producer','tenant_id':'tenant-yunshan','status':'PROCESSED',
        'payload':copy.deepcopy(payload),'receipt':{'eventId':'event','applicationId':'app','remoteApplicationId':'remote','status':'PERSISTED'}}],
        'applications':[{'remote_application_id':'remote','source_event_id':'event','source_application_id':'app',
            'source_operation_id':'op','draft_version':2,'order_no':'A10001','producer_id':'producer','tenant_id':'tenant-yunshan',
            'user_statement':copy.deepcopy(expected['body']['userStatement']),'status':'PENDING_REVIEW'}]}
    return expected,sender,receiver


class StageTwoEvidenceTest(unittest.TestCase):
    def test_valid_review_is_uncertain_even_with_remote_commit(self):
        self.assertEqual(verdict(verify(*fixture(),'REVIEW')),PASS)

    def test_m4_requires_saved_remote_receipt_and_operator_audit(self):
        e,s,r=fixture();self.assertEqual(verdict(verify(e,s,r,'M4')),FAIL)
        s['outbox'][0].update(status='DELIVERED',remote_application_id='remote',delivered_at='time')
        s['checks']=[{'finding':'PERSISTED','status':'RECORDED','repaired':True,'requested_by':9001}]
        self.assertEqual(verdict(verify(e,s,r,'M4')),PASS)

    def test_missing_or_duplicate_business_rows_never_pass(self):
        for side,keys in [(1,['tasks','operations','applications','outbox','revisions','confirmations']),(2,['inbox','applications'])]:
            for key in keys:
                for mode in ['missing','duplicate']:
                    with self.subTest(side=side,key=key,mode=mode):
                        values=fixture();rows=values[side][key];values[side][key]=[] if mode=='missing' else rows*2
                        self.assertEqual(verdict(verify(*values,'REVIEW')),FAIL)

    def test_correct_counts_but_stale_v1_statement_fails(self):
        e,s,r=fixture();s['applications'][0]['body_snapshot']['userStatement']['userDescription']='外壳开裂'
        self.assertEqual(verdict(verify(e,s,r,'REVIEW')),FAIL)

    def test_wrong_owner_or_version_or_delivery_scope_fails(self):
        for table,key,value in [('tasks','user_id',2002),('tasks','tenant_id','other'),('operations','draft_version',1),
            ('operations','delivery_profile','LOCAL_ONLY'),('confirmations','scope','SUBMIT'),('outbox','attempt_count',1)]:
            with self.subTest(table=table,key=key):
                e,s,r=fixture();s[table][0][key]=value
                self.assertEqual(verdict(verify(e,s,r,'REVIEW')),FAIL)

    def test_remote_wrong_body_ids_receipt_or_claimed_refund_fails(self):
        for table,key,value in [('applications','source_operation_id','other'),('applications','status','REFUNDED'),
            ('applications','user_statement',{}),('inbox','receipt',{}),('inbox','payload',{})]:
            with self.subTest(table=table,key=key):
                e,s,r=fixture();r[table][0][key]=value
                self.assertEqual(verdict(verify(e,s,r,'REVIEW')),FAIL)

    def test_empty_missing_unknown_and_blocked_are_not_pass(self):
        self.assertEqual(verdict({}),NOT_RUN)
        self.assertEqual(verdict({'case':'UNKNOWN'}),NOT_RUN)
        self.assertEqual(verdict({'a':PASS,'b':BLOCKED}),BLOCKED)
        self.assertEqual(verdict({'a':BLOCKED,'b':FAIL}),FAIL)
        with self.assertRaises(ValueError):verify(*fixture(),'unknown')

if __name__=='__main__':unittest.main()
