"""第十九章：仅根据本轮实际执行证据评分；语义评审和发布批准绝不由命中率代替。"""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

PASS, FAIL, NOT_RUN = 'PASS', 'FAIL', 'NOT_EXECUTED'


def coverage(groups, documents):
    """每组中的 ID 为等价证据；需要所有组都命中才算整题覆盖，重复文档不重复计分。"""
    if any(not group or any(not isinstance(x, str) or not x for x in group) for group in groups):
        raise ValueError('证据组不能为空或包含无效 ID')
    if documents is None:
        return {'status': NOT_RUN, 'covered': None, 'required': len(groups), 'rate': None, 'complete': None}
    if not groups:
        return {'status': 'NOT_APPLICABLE', 'covered': 0, 'required': 0, 'rate': None, 'complete': None}
    ids = {f"{d['sourceId']}@{d['sourceVersion']}#{d['chunkIndex']}" for d in documents}
    hit = sum(bool(set(group) & ids) for group in groups)
    return {'status': PASS if hit == len(groups) else FAIL, 'covered': hit, 'required': len(groups), 'rate': hit/len(groups), 'complete': hit == len(groups)}


def junit(directory):
    """读取已复制到本轮目录的 XML。空报告、跳过和失败都不能算通过。"""
    tests = {}
    for path in Path(directory).glob('TEST-*.xml'):
        root = ET.parse(path).getroot()
        for case in root.iter('testcase'):
            key = case.attrib['classname'] + '#' + case.attrib['name']
            status = FAIL if case.find('failure') is not None or case.find('error') is not None else NOT_RUN if case.find('skipped') is not None else PASS
            tests[key] = status
    return tests


def gate(tests, selectors):
    """支持整类或精确方法；缺失选择器立即保留未执行，不把不存在的测试当作空集合通过。"""
    states = []
    for selector in selectors:
        found = [v for k, v in tests.items() if k == selector or k.startswith(selector+'#')]
        states.extend(found or [NOT_RUN])
    return FAIL if FAIL in states else NOT_RUN if NOT_RUN in states or not states else PASS


def rag_scores(dataset, live):
    """分开保留多路召回、融合候选、排序和最终 Prompt 覆盖，正文只在受限原始报告。"""
    actual = {row['caseId']: row for row in live.get('rag', [])}
    scores = []
    for case in dataset['cases']:
        row = actual.get(case['caseId'], {})
        response = row.get('response') or {}
        rerank = response.get('reranking') or {}
        expansion = response.get('expansion') or {}
        branches = expansion.get('retrievals')
        recalled = [d for b in branches for d in b.get('documents', [])] if branches is not None else None
        scores.append({'caseId': case['caseId'], 'requestStatus': FAIL if 'errorType' in row else PASS if response else NOT_RUN,
            'elapsedMs': row.get('elapsedMs'), 'stages': {
                'vectorBranches': coverage(case['acceptableEvidenceGroups'], recalled),
                'hybridCandidates': coverage(case['acceptableEvidenceGroups'], rerank.get('before')),
                'ranked': coverage(case['acceptableEvidenceGroups'], rerank.get('after')),
                'finalPrompt': coverage(case['acceptableEvidenceGroups'], response.get('references'))},
            'rerankStatus': rerank.get('status', NOT_RUN), 'businessStatus': response.get('status'),
            'expectedRefusal': case.get('expectedRefusal', False),
            'semanticReview': NOT_RUN, 'note': '证据覆盖不是回答完整性；自然语言拒答也不能仅凭 NO_EVIDENCE 状态判分'})
    final = [r['stages']['finalPrompt'] for r in scores if r['stages']['finalPrompt']['status'] in (PASS, FAIL)]
    return {'cases': scores, 'groupCoverage': sum(s['covered'] for s in final)/sum(s['required'] for s in final) if final else None,
        'completeQuestionRate': sum(s['complete'] for s in final)/len(final) if final else None,
        'scoredQuestions': len(final), 'semanticReview': NOT_RUN}


def percentiles(rows):
    """最近秩 P50/P95，仅描述本轮各类小样本；无样本时留空，不输出伪造容量门槛。"""
    import math
    values = sorted(row['elapsedMs'] for row in rows if isinstance(row.get('elapsedMs'), (int, float)))
    return {'count': len(values), 'concurrency': 1, 'p50Ms': values[math.ceil(len(values)*.5)-1] if values else None,
        'p95Ms': values[math.ceil(len(values)*.95)-1] if values else None,
        'capacityConclusion': 'NOT_EVALUATED', 'modelCalls': None, 'tokens': None, 'databaseQueries': None}


def live_contracts(live):
    """按固定用例检查结构化事实和原范围，不能替代对自然语言条件/例外的人工评审。"""
    manifest={d['documentId']:d['metadata'] for d in live.get('knowledgeManifest',[])}
    refs=[d for r in live.get('rag',[]) for d in (r.get('response') or {}).get('references',[])]
    scope=all((m:=manifest.get(d['documentId'],{})).get('tenantId')=='tenant-yunshan'
        and m.get('status')=='PUBLISHED' and m.get('sourceVersion')==d['sourceVersion'] for d in refs)
    checks={'evidenceScope':PASS if scope and live.get('rag') else NOT_RUN if not live.get('rag') else FAIL}
    rows={r['question']:r for r in live.get('formalHttp',[])}
    questions=['你好','A10001 发货了吗？','A10002 发货了吗？','A10001 的商品有质量问题，能退吗？','我要转人工']
    for index,question in enumerate(questions):
        row=rows.get(question);key=['greeting','ownOrder','foreignOrder','readOnlyAssessment','handoff'][index]
        if row is None:checks[key]=NOT_RUN;continue
        d=row.get('response') or {};p=(d.get('message') or {}).get('payload') or {};order=(p.get('order') or {}).get('lookup') or {}
        if index==0:ok=d.get('published') is True and (p.get('decision') or {}).get('route')=='SMALL_TALK' and (p.get('routing') or {}).get('classifierCalls')==0 and p.get('order') is None and p.get('knowledge') is None
        elif index==1:ok=order.get('code')=='FOUND' and order.get('status')=='SHIPPED'
        elif index==2:ok=order.get('code')=='NOT_FOUND' and order.get('status') is None and order.get('expectedDeliveryDate') is None
        elif index==3:
            a=p.get('afterSale') or {};results=a.get('assessments') or []
            ok=len(results)==1 and results[0].get('status')=='NEED_QUALITY_VERIFICATION' and results[0].get('refundExecuted') is False and (results[0].get('verifiedFacts') or {}).get('qualityVerification')=='UNVERIFIED' and results[0].get('checkedAt')=='2026-08-30T02:00:00Z' and p.get('answer')==a.get('answer')
        else:ok=d.get('published') is False and (d.get('receipt') or {}).get('mode')=='WAITING_HUMAN' and bool((d.get('receipt') or {}).get('handoffId'))
        checks[key]=PASS if ok else FAIL
    return checks
