package com.reagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ReAgent —— 自研可恢复 Agent 运行时。
 * M1 目标:跑通最小 ReAct 闭环(LLM + 循环 + 工具),状态先放内存,不落库。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ReAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReAgentApplication.class, args);
    }
}
