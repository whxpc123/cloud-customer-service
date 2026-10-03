package com.example.cloudcustomerservice.agent.persistence;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 每次新建无历史缓存的保存器，连接与 Flyway 同源；不另外复制用户名和口令配置。 */
@Component @Profile("local & knowledge")
public class PostgresSaverFactory {
    private final JdbcConnectionDetails connection;
    public PostgresSaverFactory(JdbcConnectionDetails connection) { this.connection=connection; }

    /** 使用固定版本真实 Builder。它没有 dataSource 方法，不能把连接池参数误当成已生效。 */
    public PostgresSaver create() {
        String url=connection.getJdbcUrl();
        if(!url.startsWith("jdbc:postgresql://"))throw new IllegalStateException("检查点需要明确的 PostgreSQL JDBC 地址");
        // Builder 只支持单主机五项连接信息。拒绝会被它丢弃的 SSL/search_path 等参数；测试容器仅附加日志参数。
        int query=url.indexOf('?');
        if(query>=0&&!url.substring(query+1).equals("loggerLevel=OFF"))
            throw new IllegalStateException("当前检查点适配器不支持 JDBC 连接参数，请先核对保存器配置");
        var parsed=new PGSimpleDataSource();parsed.setUrl(query<0?url:url.substring(0,query));
        if(parsed.getServerNames().length!=1||parsed.getPortNumbers().length!=1)
            throw new IllegalStateException("当前检查点实验只支持单个 PostgreSQL 地址");
        return PostgresSaver.builder().host(parsed.getServerNames()[0]).port(parsed.getPortNumbers()[0])
                .database(parsed.getDatabaseName()).user(connection.getUsername()).password(connection.getPassword())
                .stateSerializer(StateGraph.DEFAULT_JACKSON_SERIALIZER)
                .createTables(false).dropTablesFirst(false).build();
    }
}
