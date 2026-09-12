# cloud-customer-service

当前版本：**第 1 章——Spring Boot → ChatClient → ChatModel → DashScope → Qwen**。

章节记录：[第一章](docs/chapters/01-first-chat.md)。变化见 [CHANGELOG](CHANGELOG.md)。

前三章历史于 2026-09-12 根据已实现代码补建，此标签对应第 1 章的可运行代码，不表示此前已经实时提交。

## IDEA 启动

1. 打开 `pom.xml`，等待 Maven 依赖同步。
2. Project SDK 选择 JDK 17，Maven 选择 Wrapper。
3. 选择 `CloudCustomerServiceApplication` 运行配置，点击运行。
4. 应用读取环境变量 `DASHSCOPE_API_KEY`。本机已验证 IDEA 可继承现有变量，无需在代码中填写 Key。
5. 访问 <http://localhost:18080/api/chat?message=你好>。

本机 8080 / 8081 已被占用，默认端口为 18080，可用 `SERVER_PORT` 修改。

## 版本与构建

Java 17，Spring Boot 3.5.8，Spring AI 1.1.2，Spring AI Alibaba 1.1.2.2，Maven Wrapper 3.9.11，模型 qwen-plus。

```bash
./mvnw test
./mvnw package
./mvnw spring-boot:run
```

运行需设置真实 `DASHSCOPE_API_KEY`；自动测试不需要真实 Key，也不访问模型。本章共 2 项自动测试，测试通过。

`requests.http` 提供本章接口实验。第一章只提供通用聊天，没有客服身份或记忆。

每章使用独立提交和 `chapter-NN` 标签，后续章节按用户提供的内容实现。真实模型调用会使用百炼额度。
