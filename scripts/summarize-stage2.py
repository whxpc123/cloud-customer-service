#!/usr/bin/env python3
"""将本轮端到端证据与新鲜 JUnit 合同证据汇总；输出脱敏公开副本，不伪造发布批准。"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile
from acceptance.report import junit, gate
from stage2.evidence import verdict, PASS, FAIL, NOT_RUN, BLOCKED

ROOT=Path(__file__).resolve().parents[1]
# 整类覆盖失败注入、真实锁等待、HTTP身份、事务、旧租约和旧核查防护；模拟远端的类明确属于组件层。
GROUPS={
 'draft_version_and_publish_races':['agent.DraftRevisionTest'],
 'approval_scope_expiry_and_submit_races':['agent.SubmissionPersistenceTest'],
 'application_outbox_atomicity_and_lease_fencing':['agent.OutboxPersistenceTest'],
 'reconciliation_atomicity_permissions_and_stale_results':['agent.ReconciliationPersistenceTest'],
 'persistent_draft_process_restart':['agent.PersistentDraftRestartTest','agent.DraftRevisionRestartTest'],
 'receipt_outbox_reconciliation_process_restart':['agent.SubmissionRestartTest','agent.OutboxRestartTest','agent.ReconciliationRestartTest'],
 'hitl_lab_only_timeout_and_late_result':['hitl.HitlServiceLimitsTest','agent.HitlPersistenceTest']}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('run_directory',type=Path)
    p.add_argument('--root-reports',type=Path,default=ROOT/'target/surefire-reports')
    p.add_argument('--receiver-reports',type=Path,default=ROOT/'after-sale-receiver/target/surefire-reports')
    args=p.parse_args();folder=args.run_directory.resolve();data=json.loads((folder/'report.json').read_text())
    cutoff=dt.datetime.fromisoformat(data['startedAt']).timestamp();regression={};counts={}
    for name,directory in [('sender',args.root_reports),('receiver',args.receiver_reports)]:
        dest=folder/('regression-'+name);dest.mkdir(exist_ok=True)
        # 清掉上一次汇总副本，缺失报告不能被旧 PASS 填补。
        for old in dest.glob('TEST-*.xml'):old.unlink()
        for source in directory.glob('TEST-*.xml'):
            if source.stat().st_mtime>=cutoff:shutil.copy2(source,dest/source.name)
        tests=junit(dest);regression[name]=tests
        counts[name]={'passed':list(tests.values()).count(PASS),'failed':list(tests.values()).count(FAIL),
            'notRun':sum(v not in (PASS,FAIL) for v in tests.values()),'xmlFiles':len(list(dest.glob('TEST-*.xml')))}
        data['checks']['regression.'+name+'_all_observed']=verdict(tests)
    for name,selectors in GROUPS.items():
        data['checks']['component.'+name]=gate(regression['sender'],['com.example.cloudcustomerservice.'+s for s in selectors])
    for name in ['InboxIntegrationTest','InboxProtocolTest']:
        data['checks']['component.receiver_'+name]=gate(regression['receiver'],['com.example.aftersalereceiver.inbox.'+name])
    data['regression']=counts
    # 业务验收进程使用复制的生产JAR。逐项比较最终包的非静态资源/类和依赖，
    # 防止拿旧业务版本的成功替新版本背书；报告页和静态公开摘要不影响业务字节。
    binary={}
    for side,current in [('sender',ROOT/'target/cloud-customer-service-0.0.1-SNAPSHOT.jar'),
                         ('receiver',ROOT/'after-sale-receiver/target/after-sale-receiver-0.0.1-SNAPSHOT.jar')]:
        with zipfile.ZipFile(folder/(side+'.jar')) as before,zipfile.ZipFile(current) as after:
            def signatures(archive):
                return {name:hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                    if not name.endswith('/') and ((name.startswith('BOOT-INF/classes/') and not name.startswith('BOOT-INF/classes/static/'))
                        or name.startswith('BOOT-INF/lib/'))}
            a,b=signatures(before),signatures(after)
            binary[side]={'comparedEntries':len(a),'status':PASS if a and a==b else FAIL}
            data['checks']['binary.'+side]=binary[side]['status']
    data['businessBinaryEquivalence']=binary
    model_checks={str(i):row.get('status',NOT_RUN) for i,row in enumerate(data['modelCases'])}
    model_checks.update({str(i)+'.noImplicitAuthorization':row.get('noImplicitAuthorization',NOT_RUN) for i,row in enumerate(data['modelCases']) if row.get('id')!='correction'})
    data['modelSafetyGate']=verdict(model_checks)
    data['engineeringGate']=verdict({**data['checks'],'modelSafetyGate':data['modelSafetyGate']})
    data['componentEvidenceNote']='JUnit来自本轮开始后的报告。模拟模型/模拟远端属于组件或进程恢复层，不能替代两生产JAR主链。'
    review=folder/'semantic-review.json'
    if review.exists():
        review_data=json.loads(review.read_text());data['semanticReviewMetadata']={k:v for k,v in review_data.items() if k!='cases'}
        for row in data['modelCases']:
            assessment=review_data['cases'].get(row['id'],{})
            row['semanticReview']=assessment.get('status',NOT_RUN);row['reviewNote']=assessment.get('note','')
    data['humanBusinessReview']=NOT_RUN;data['productionRelease']=BLOCKED
    data['overallStage2']=verdict({'implemented':data['engineeringGate'],'formalAgentHitl':data['integrationBoundary']['agentHitlToRealSubmission']})
    data['summarizedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
    # 保存原始 report 不变；公开副本按白名单提取，无正文、口令、Cookie、JWT或检查点。
    (folder/'reviewed-report.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    keys=['schemaVersion','runId','startedAt','finishedAt','summarizedAt','businessGate','engineeringGate','checks','modelCases',
        'milestones','overallStage2','productionRelease','limits','integrationBoundary','proxy','regression','modelSafetyGate','humanBusinessReview','semanticReviewMetadata','componentEvidenceNote','businessBinaryEquivalence']
    public={k:data[k] for k in keys if k in data}
    public['identity']={k:v for k,v in data.get('expected',{}).items() if k!='body'}
    manifest=data.get('manifest',{})
    public['manifest']={k:v for k,v in manifest.items() if k not in ['databaseNames','ports']}
    public['evidenceDigests']={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in folder.glob('*.json')
        if path.name not in ['reviewed-report.json','public-report.json']}
    (folder/'public-report.json').write_text(json.dumps(public,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'runId':data['runId'],'businessGate':data['businessGate'],'engineeringGate':data['engineeringGate'],'regression':counts},ensure_ascii=False))
    return 1 if data['engineeringGate']==FAIL else 0

if __name__=='__main__':sys.exit(main())
