package com.example.cloudcustomerservice.reconcile;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.example.cloudcustomerservice.reconcile.ReconcileModel.*;
import static org.assertj.core.api.Assertions.*;

/** 不依赖模型与数据库的证据判定：只有完整且属于原申请的持久化合同才可修复。 */
class ReconcileModelTest {
    final UUID event=UUID.randomUUID(),application=UUID.randomUUID();
    Ack valid(){return new Ack(event,application,"remote-1","PERSISTED");}
    @Test void exactReceiptAndKnownNonPositiveStates(){
        assertThat(inspect(event,application,new Reply(event,"PERSISTED",valid())).finding()).isEqualTo(Finding.PERSISTED);
        for(String state:List.of("NOT_OBSERVED","PAYLOAD_CONFLICT","INCONSISTENT")) {
            assertThat(inspect(event,application,new Reply(event,state,null)).finding()).isNotEqualTo(Finding.PERSISTED);
            assertThat(inspect(event,application,new Reply(event,state,valid())).finding()).isEqualTo(Finding.INVALID_RESPONSE);
        }
    }
    @ParameterizedTest @ValueSource(strings={"event","application","status","blank","long","null"})
    void wrongReceiptIsInvalid(String field){
        var ack=new Ack(field.equals("event")?UUID.randomUUID():event,field.equals("application")?UUID.randomUUID():application,
            switch(field){case "blank"->" ";case "long"->"x".repeat(201);case "null"->null;default->"remote";},field.equals("status")?"ACCEPTED":"PERSISTED");
        assertThat(inspect(event,application,new Reply(event,"PERSISTED",ack)).finding()).isEqualTo(Finding.INVALID_RESPONSE);
    }
    @Test void missingUnknownAndContradictoryEnvelopeIsInvalid(){
        assertThat(inspect(event,application,null).finding()).isEqualTo(Finding.INVALID_RESPONSE);
        for(var reply:List.of(new Reply(null,"PERSISTED",valid()),new Reply(UUID.randomUUID(),"PERSISTED",valid()),new Reply(event,null,null),new Reply(event,"UNKNOWN",null),new Reply(event,"PERSISTED",null)))
            assertThat(inspect(event,application,reply).finding()).isEqualTo(Finding.INVALID_RESPONSE);
    }
    @Test void observationsCannotSmuggleUntrustedTextOrRemoteIds(){
        assertThatThrownBy(()->new Evidence(Finding.PERSISTED,null,"VERIFIED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Evidence(Finding.NOT_OBSERVED,"invented","NOT_OBSERVED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Evidence(Finding.QUERY_UNAVAILABLE,null,"sensitive body\n")).isInstanceOf(IllegalArgumentException.class);
    }
}
