#!/usr/bin/env python3
"""第一阶段验收入口：默认离线回归，--live 显式真实模型评测，--proxy 显式本机 Nginx 复测。"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
from acceptance.report import junit, gate, rag_scores, live_contracts, percentiles, PASS, FAIL, NOT_RUN

ROOT=Path(__file__).resolve().parents[1]
PREFIX='com.example.cloudcustomerservice.'
# 门槛绑定真实测试类或方法，不维护容易失真的手填“全绿”列表。
GATES={
    'identity_and_scope':['HumanHandoffPersistenceTest#forgedBodyAndHeaderCannotChangeOwnerHistoryOrMode','acceptance.HandoffAcceptanceTest#otherUsersTenantsAndForgedActorCannotMutate','SseHttpIntegrationTest#ownershipLoginAndCsrfFailBeforeOpeningPrivateStream','HybridSearchPersistenceTest#exactAndKeywordExcludeArchivedDraftOtherTenantsBasesAndLanguages'],
    'readonly_after_sale':['AfterSaleTest','CustomerOrderToolsTest'],
    'handoff_idempotency_and_locks':['acceptance.HandoffAcceptanceTest#concurrentRequestsWaitForRealRowLockAndReuseReceipt','acceptance.HandoffAcceptanceTest#concurrentAgentsWaitThenOnlyOneCanAccept'],
    'late_answer_not_published':['HumanHandoffPersistenceTest#handoffDuringGenerationCommitsAndLateCandidateNeverPublishes','HumanHandoffPersistenceTest#waitingAndActiveSaveMessagesWithoutAnyClassifierOrModel'],
    'failure_contracts':['routing.RoutingClassifierTest','RerankingTest','EvidenceRequiredAdvisorTest','OrderToolConversationTest'],
    'http_stream_auth_cancel_error':['SseHttpIntegrationTest'],
}


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest():
    """按内容固定 Prompt、迁移、夹具、配置和验收代码；不读取 .local 的口令或环境变量值。"""
    files={}
    for base in ['src/main','src/test','scripts']:
        for p in (ROOT/base).rglob('*'):
            if p.is_file() and '__pycache__' not in p.parts:files[str(p.relative_to(ROOT))]=digest(p)
    for name in ['pom.xml','compose.yml']:files[name]=digest(ROOT/name)
    encoded=json.dumps(files,sort_keys=True).encode()
    pom=ET.parse(ROOT/'pom.xml').getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
    versions={key:pom.findtext(path,namespaces=ns) for key,path in {'springBoot':'m:parent/m:version','springAi':'m:properties/m:spring-ai.version','springAiAlibaba':'m:properties/m:spring-ai-alibaba.version'}.items()}
    return {'baseCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'dirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()),
        'contentSha256':hashlib.sha256(encoded).hexdigest(),'filesSha256':files,
        'java':subprocess.run([str(Path(os.environ.get('JAVA_HOME',''))/'bin/java') if os.environ.get('JAVA_HOME') else 'java','-version'],capture_output=True,text=True).stderr.strip(),
        'versionsFromPom':versions,
        'fixtureClock':'2026-08-30T10:00:00+08:00','scope':'local teaching system; no production release approval'}


def command(folder,name,args,extra=None):
    """不打印完整模型回答；本轮日志 0600，失败保留退出码，不复用旧报告。"""
    print('正在执行：'+name,flush=True)
    env=os.environ.copy();env.update(extra or {})
    with (folder/(name+'.log')).open('w') as log:
        result=subprocess.run(args,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    return {'status':PASS if result.returncode==0 else FAIL,'exitCode':result.returncode,'log':name+'.log'}


def copy_reports(folder,name):
    dest=folder/name;shutil.copytree(ROOT/'target/surefire-reports',dest,dirs_exist_ok=True) if (ROOT/'target/surefire-reports').exists() else dest.mkdir(exist_ok=True)
    return junit(dest)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--live',action='store_true');parser.add_argument('--proxy',action='store_true')
    args=parser.parse_args();os.umask(0o077)
    folder=ROOT/'.local/acceptance'/(dt.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]);folder.mkdir(parents=True)
    data={'runId':folder.name,'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'manifest':manifest(),'steps':{},'productionRelease':'BLOCKED'}
    (folder/'manifest.json').write_text(json.dumps(data['manifest'],ensure_ascii=False,indent=2))
    # 先 clean，确保旧实验 XML 不会混入本轮测试数量；运行中的 JAR 应位于 target 之外。
    data['steps']['java']=command(folder,'java',['./mvnw','-B','clean','package'],{'RUN_PGVECTOR_TESTS':'true','RUN_ACCEPTANCE_TESTS':'true','RUN_NGINX_TESTS':'false'})
    tests=copy_reports(folder,'java-reports')
    data['javaTests']={s:list(tests.values()).count(s) for s in [PASS,FAIL,NOT_RUN]}
    data['hardGates']={name:gate(tests,[PREFIX+s for s in selectors]) for name,selectors in GATES.items()}
    if (ROOT/'target/acceptance-database.json').exists():shutil.copy2(ROOT/'target/acceptance-database.json',folder/'database.json')
    data['steps']['javascript']=command(folder,'javascript',['node','--test','scripts/tests/sse-client.test.mjs'])
    data['steps']['reportTests']=command(folder,'report-tests',[sys.executable,'-m','unittest','discover','-s','scripts/tests','-p','test_*.py'])
    if args.proxy and data['steps']['java']['status']==PASS:
        shutil.rmtree(ROOT/'target/surefire-reports')
        data['steps']['proxy']=command(folder,'proxy',['./mvnw','-B','-Dtest=SseHttpIntegrationTest','test'],{'RUN_PGVECTOR_TESTS':'true','RUN_NGINX_TESTS':'true'})
        proxy=copy_reports(folder,'proxy-reports');data['hardGates']['nginx_http_stream']=gate(proxy,[PREFIX+'SseHttpIntegrationTest'])
    else:data['hardGates']['nginx_http_stream']=NOT_RUN
    live={}
    if args.live and data['steps']['java']['status']==PASS:
        shutil.rmtree(ROOT/'target/surefire-reports',ignore_errors=True)
        data['steps']['live']=command(folder,'live',['./mvnw','-B','-Dtest=StageOneLiveExperiment','test'],{'RUN_LIVE_ACCEPTANCE':'true'})
        copy_reports(folder,'live-reports')
        path=ROOT/'target/acceptance-live.json'
        if path.exists():
            live=json.loads(path.read_text());shutil.copy2(path,folder/'live-raw.json')
    data['liveContracts']=live_contracts(live)
    data['rag']=rag_scores(json.loads((ROOT/'src/test/resources/acceptance/knowledge-cases.json').read_text()),live)
    data['performance']={'rag':percentiles(live.get('rag',[])),'formalByQuestion':[{k:r[k] for k in ['question','elapsedMs'] if k in r} for r in live.get('formalHttp',[])],
        'note':'小样本、单并发；异类请求不合并计算统一延迟，未统计值为 null'}
    data['engineeringGate']=FAIL if any(v==FAIL for v in data['hardGates'].values()) or any(s['status']==FAIL for s in data['steps'].values()) or FAIL in data['liveContracts'].values() else NOT_RUN if NOT_RUN in data['hardGates'].values() else PASS
    data['releaseBlockers']=['旧实验 API 保留教学权限，未统一生产认证','真实订单/客服消息通道未接入','云端精排业务空间尚待配置与验收','代表性语义人工评审及效果门槛未约定','生产并发容量、监控、灰度及回滚演练未执行']
    if manifest()['contentSha256']!=data['manifest']['contentSha256']:
        data['engineeringGate']=FAIL;data['releaseBlockers'].append('验收期间代码发生变化，必须重新运行')
    data['finishedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
    (folder/'report.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
    lines=['# 第一阶段实际验收记录','',f"运行编号：`{data['runId']}`",f"工程硬门槛：**{data['engineeringGate']}**；生产发布：**BLOCKED**",'',
        '| 门槛 | 结果 |','| --- | --- |',*[f'| {k} | {v} |' for k,v in data['hardGates'].items()], '',
        f"Java：{data['javaTests']}。详细测试名、日志、版本哈希和原始轨迹保存在本目录。",'',
        f"知识证据组覆盖：{data['rag']['groupCoverage']}；整题完整覆盖：{data['rag']['completeQuestionRate']}；语义人工评审：NOT_EXECUTED。",'',
        '发布缺口：',*[f'- {x}' for x in data['releaseBlockers']], '', 'PASS 仅表示对应执行证据通过；NOT_EXECUTED 不能视为通过。模型调用成功不代表回答已经逐句核验。']
    (folder/'report.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps({'report':str(folder/'report.md'),'engineeringGate':data['engineeringGate'],'productionRelease':'BLOCKED'},ensure_ascii=False))
    return 1 if data['engineeringGate']==FAIL or FAIL in data['liveContracts'].values() else 0

if __name__=='__main__':raise SystemExit(main())
