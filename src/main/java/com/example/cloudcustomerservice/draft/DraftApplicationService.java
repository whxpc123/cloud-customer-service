package com.example.cloudcustomerservice.draft;

import com.example.cloudcustomerservice.agent.PersistentDraftTaskService.Completed;
import com.example.cloudcustomerservice.agent.DraftRun;
import com.example.cloudcustomerservice.agent.DraftTaskModel.Access;
import com.example.cloudcustomerservice.handoff.HandoffModel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.draft.DraftModels.*;

/** 源候选从业务数据库取得，整理在事务外完成；正文与确认只能经此授权服务进入存储层。 */
@Service @Profile("local & knowledge") @Transactional(propagation=Propagation.NEVER)
public class DraftApplicationService {
    private final DraftVersionStore store;
    private final DraftTextExtractor extractor;
    private final ObjectMapper json;
    public DraftApplicationService(DraftVersionStore store,DraftTextExtractor extractor,ObjectMapper json){this.store=store;this.extractor=extractor;this.json=json;}

    /** 只接受任务编号和用户看到的任务版本，不接收客户端候选、事实、确认人或版本正文。 */
    public View generate(Access access,UUID id,Long expectedTaskVersion) {
        if(expectedTaskVersion==null||expectedTaskVersion<0)throw new IllegalArgumentException("需要当前任务版本");
        var basis=store.source(access.actor(),id);var receipt=reception(access,basis);
        DraftVersionStore.requireCandidate(basis);
        if(basis.taskVersion()!=expectedTaskVersion)throw conflict();
        var completed=completed(basis);
        var statement=extractor.extract(id,completed.run().candidateText());
        // 只把模型返回的两个文字字段放进 userStatement，事实保持完整的服务器快照。
        var body=new Body(1,basis.orderNo(),statement,completed.run().assessment(),NOTICE);
        var now=reception(access,basis);if(now.version()!=receipt.version())throw conflict();
        return store.publish(access.actor(),basis,receipt.version(),body);
    }
    /** 读取明确版本，不执行模型或图；历史确认与当前效力分开返回。 */
    public View read(Access access,UUID id,Long version){positive(version);var basis=store.source(access.actor(),id);
        return store.read(access.actor(),id,version,reception(access,basis).version());}
    public List<RevisionSummary> list(Access access,UUID id){var basis=store.source(access.actor(),id);
        return store.list(access.actor(),id,reception(access,basis).version());}
    /** 确认完全由程序处理，既不调用模型，也不注册成 Agent 工具。 */
    public Confirmation confirm(Access access,UUID id,Long version,Boolean accepted){positive(version);
        if(!Boolean.TRUE.equals(accepted))throw new IllegalArgumentException("请明确接受具体版本");
        var basis=store.source(access.actor(),id);
        return store.confirm(access.actor(),id,version,true,reception(access,basis).version());}

    /** 校验保存的候选与任务、运行和事实关联；读取异常时不让模型重新编造缺失事实。 */
    private Completed completed(DraftVersionStore.Basis basis) {
        try {
            var saved=json.readValue(basis.resultJson(),Completed.class);var run=saved.run();
            if(run==null||!Objects.equals(basis.runId().toString(),run.runId())||run.status()!=DraftRun.Status.CANDIDATE_UNVALIDATED
                    ||run.submitted()||run.refundExecuted()||run.candidateText()==null||run.candidateText().isBlank())throw new IllegalStateException();
            var assessment=run.assessment();
            if(assessment==null||assessment.status()==null||assessment.verifiedFacts()==null||assessment.checkedAt()==null
                    ||assessment.evidence().isEmpty()||assessment.refundExecuted()||!basis.orderNo().equals(assessment.verifiedFacts().orderNo())
                    ||basis.reason()!=assessment.claimedReason())throw new IllegalStateException();
            return saved;
        }catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"已存候选与任务或事实不一致，未调用模型或保存草稿，请核查");}
    }
    /** 回调沿用捕获的当前 Session 身份；交接/注销后不能用模型开始前的授权继续发布。 */
    private Receipt reception(Access access,DraftVersionStore.Basis basis){var receipt=access.reception().apply(basis.conversationId());
        if(receipt.mode()!=Mode.BOT)throw new ResponseStatusException(HttpStatus.CONFLICT,"当前接待状态不允许草稿操作");return receipt;}
    private static void positive(Long version){if(version==null||version<=0)throw new IllegalArgumentException("需要具体的正数草稿版本");}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"任务或接待版本已变化，请刷新重新核对；未自动重试");}
}
