// src/main/java/com/alibaba/nacos/example/NamingExample.java
package com.alibaba.nacos.example;

import java.util.Properties;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.Cluster;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;

public class NamingExample {

    public static void main(String[] args) throws NacosException {

        Properties properties = new Properties();
        // 从 JVM 系统属性读取 serverAddr 与 namespace，例如 -DserverAddr=127.0.0.1:8848 -Dnamespace=public
	String serverAddr = System.getProperty("serverAddr", "127.0.0.1:8848");
	String namespace = System.getProperty("namespace", "public");
        properties.setProperty("serverAddr", serverAddr);
        properties.setProperty("namespace", namespace);

        // 如需鉴权（启用 Nacos auth 时），按需加入：
        // properties.setProperty("username", System.getProperty("username", "nacos"));
        // properties.setProperty("password", System.getProperty("password", "nacos"));

        NamingService naming = NamingFactory.createNamingService(properties);

	// 等待连接建立
	long deadline = System.currentTimeMillis() + 15000; // 最多等15秒
	while (!"UP".equalsIgnoreCase(naming.getServerStatus()) && System.currentTimeMillis() < deadline) {
		try {
			Thread.sleep(1000);
			System.out.println("nacos status=" + naming.getServerStatus());
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt(); // 重新设置中断标志
			throw new IllegalStateException("Interrupted while waiting for Nacos to be UP", ie);
		}
	}


	Instance ins = new Instance();
	ins.setIp("127.0.0.1");
	ins.setPort(3306);
	ins.setEphemeral(false);
	naming.registerInstance("dr.mysql", ins);

        // 注册两个实例到同一服务名的不同分组
        System.out.println("All instances after register:");
        System.out.println(naming.getAllInstances("dr.mysql"));

        // 订阅服务变更
        naming.subscribe("dr.mysql", new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (event instanceof NamingEvent) {
                    NamingEvent ne = (NamingEvent) event;
                    System.out.println("Service changed: " + ne.getServiceName());
                    System.out.println("New instances: " + ne.getInstances());
                } else {
                    System.out.println("Unknown event: " + event);
                }
            }
        });

        // 为了演示订阅回调，阻止 main 线程立即退出，可按需阻塞或加入显式等待
        try {
            Thread.sleep(60_000L);
        } catch (InterruptedException ignored) {
        }
    }
}
