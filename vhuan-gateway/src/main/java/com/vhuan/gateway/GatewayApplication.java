package com.vhuan.gateway;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

@SpringBootApplication
@ComponentScan(basePackages = {"com.vhuan.gateway", "com.vhuan.common"})
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * 服务发现预热器 —— 在网关启动后主动触发下游服务实例的第一次查询，
     * 把 DiscoveryClient（Nacos NamingService）的懒初始化开销从"用户第一次请求"
     * 挪到"应用启动阶段"，避免"首请求超时、二请求成功"的现象。
     *
     * 工作原理：
     *   Spring Cloud Gateway 的 ReactiveLoadBalancerClientFilter 在转发前会调用
     *   DiscoveryClient.getInstances(serviceId) 从 Nacos 拉取服务实例列表。
     *   这个过程第一次调用时需要初始化 NamingService、建立到 Nacos 的 HTTP 连接，
     *   耗时较长；此后查询会走本地缓存，速度极快。
     *   本 Bean 提前主动调用一次 getInstances()，将初始化开销前置到启动阶段。
     *
     * 容错策略：
     *   1. 若下游服务此时尚未启动（Nacos 查不到实例），getInstances() 会返回空列表，
     *      不会抛出异常，也不阻塞网关启动。
     *   2. 若 Nacos 本身不可达导致查询异常，由 catch 静默吞掉，不影响启动流程。
     *   3. 等下游服务就绪后，用户的第一次真实请求仍会兜底完成初始化，
     *      配合 application.yaml 中延长的 connect-timeout / response-timeout，
     *      双重保险避免首请求超时。
     */
    @Bean
    public ApplicationRunner discoveryWarmer(DiscoveryClient discoveryClient) {
        return args -> {
            // 需要预热的下游服务名（与路由 uri 中的 lb:// 服务标识一致，见 application.yml）
            List<String> services = List.of(
                    "vhuan-auth", "vhuan-tenant", "vhuan-agent", "vhuan-campaign",
                    "vhuan-call", "vhuan-contact", "vhuan-analytics", "vhuan-notification",
                    "vhuan-sip-connector"
            );
            for (String serviceId : services) {
                try {
                    // 触发 Nacos 服务实例查询，初始化 NamingService 和本地缓存
                    // 返回值（实例列表）不需要处理——能完成调用本身就是预热的目的
                    discoveryClient.getInstances(serviceId);
                } catch (Exception e) {
                    // 忽略所有异常：Nacos 或下游服务未就绪时，等第一次真实请求再初始化即可
                }
            }
        };
    }
}
