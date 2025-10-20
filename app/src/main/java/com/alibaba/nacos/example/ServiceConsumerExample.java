
package com.alibaba.nacos.example;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.apache.commons.cli.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;

/**
 * 服务消费者示例类，用于发现服务、选择最佳实例并连接。
 */
public class ServiceConsumerExample {

    // 原子引用，用于保存当前正在使用的服务实例
    private static final AtomicReference<Instance> currentInstance = new AtomicReference<>();
    // 数据库连接对象，volatile确保多线程下的可见性
    private static volatile Connection connection;
    // 单线程执行器，用于异步执行数据库连接任务
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();


    public static void main(String[] args) throws NacosException, InterruptedException {
        // 创建命令行参数解析器
        Options options = new Options();
        options.addOption("s", "serverAddr", true, "Nacos server address");
        options.addOption("n", "namespace", true, "Nacos namespace");
        options.addOption("sn", "serviceName", true, "Service name to discover");
        options.addOption("h", "help", false, "Print this message");
        options.addOption("dc", "datacenter", true, "Preferred datacenter");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            // 解析命令行参数
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("ServiceConsumerExample", options);
            System.exit(1);
            return;
        }

        // 如果用户请求帮助信息，则打印并退出
        if (cmd.hasOption("help")) {
            formatter.printHelp("ServiceConsumerExample", options);
            System.exit(0);
        }

        // 获取命令行参数值，如果未提供则使用默认值
        String serverAddr = cmd.getOptionValue("serverAddr", "127.0.0.1:8848");
        String namespace = cmd.getOptionValue("namespace", "public");
        String serviceName = cmd.getOptionValue("serviceName", "dr.mysql");
        String preferredDatacenter = cmd.getOptionValue("datacenter");

        // 设置Nacos连接属性
        Properties properties = new Properties();
        properties.setProperty("serverAddr", serverAddr);
        properties.setProperty("namespace", namespace);

        // 创建Nacos命名服务客户端
        NamingService namingService = NamingFactory.createNamingService(properties);

        // 等待Nacos服务器启动，最长等待15秒
        long deadline = System.currentTimeMillis() + 15000;
        while (!"UP".equalsIgnoreCase(namingService.getServerStatus()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1000);
                System.out.println("Nacos server status: " + namingService.getServerStatus());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Nacos to be UP", ie);
            }
        }
        // 如果15秒后仍未连接成功，则报错退出
        if (!"UP".equalsIgnoreCase(namingService.getServerStatus())) {
            System.err.println("Failed to connect to Nacos server.");
            System.exit(1);
        }

        System.out.println("Successfully connected to Nacos server.");

        // 订阅服务，并设置监听器
        namingService.subscribe(serviceName, new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (event instanceof NamingEvent) {
                    System.out.println("Service " + serviceName + " has changed.");
                    List<Instance> instances = ((NamingEvent) event).getInstances();
                    System.out.println("Found " + instances.size() + " instances for service: " + serviceName);
                    System.out.println(instances);

                    // 自定义逻辑，选择最佳实例
                    Instance selectedInstance = selectBestInstance(instances, preferredDatacenter);

                    if (selectedInstance != null) {
                        Instance oldInstance = currentInstance.get();

                        // 如果选中的实例与当前实例不同，则进行切换
                        if (oldInstance == null || !oldInstance.equals(selectedInstance)) {
                            System.out.println("Switching to new instance: " + selectedInstance.getIp() + ":" + selectedInstance.getPort() +
                                    " with weight " + selectedInstance.getWeight() +
                                    " in datacenter " + selectedInstance.getMetadata().get("datacenter"));
                            currentInstance.set(selectedInstance);
                            disconnect(); // 断开旧连接
                            executor.submit(() -> runConsumer(selectedInstance)); // 异步连接新实例
                        }
                    } else {
                        // 如果没有可用的实例
                        System.err.println("No available instances found for service: " + serviceName);
                        currentInstance.set(null);
                        disconnect();
                    }
                }
            }
        });

        // 保持主线程存活
        while (true) {
            Thread.sleep(1000);
        }
    }

    /**
     * 根据预设策略选择最佳服务实例。
     * @param instances 候选实例列表
     * @param preferredDatacenter 首选数据中心
     * @return 选中的最佳实例，如果没有则返回null
     */
    private static Instance selectBestInstance(List<Instance> instances, String preferredDatacenter) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        // 过滤出健康、启用且权重>0的实例
        List<Instance> healthyInstances = instances.stream()
                .filter(Instance::isHealthy)
                .filter(Instance::isEnabled)
                .filter(instance -> instance.getWeight() > 0)
                .collect(java.util.stream.Collectors.toList());

        // 如果没有符合条件的实例，打印日志并返回null
        if (healthyInstances.isEmpty()) {
            System.err.println("No healthy instances available with weight > 0. Original instances count: " + instances.size());
            instances.forEach(instance -> {
                System.err.println("  - Instance: " + instance.getIp() + ":" + instance.getPort() +
                        ", Healthy: " + instance.isHealthy() +
                        ", Enabled: " + instance.isEnabled() +
                        ", Weight: " + instance.getWeight());
            });
            return null;
        }

        // 按数据中心将实例分组
        List<Instance> localInstances = new java.util.ArrayList<>();
        List<Instance> remoteInstances = new java.util.ArrayList<>();

        if (preferredDatacenter != null) {
            for (Instance instance : healthyInstances) {
                if (preferredDatacenter.equals(instance.getMetadata().get("datacenter"))) {
                    localInstances.add(instance);
                } else {
                    remoteInstances.add(instance);
                }
            }
        } else {
            // 如果没有指定首选数据中心，所有实例都视为远程实例
            remoteInstances.addAll(healthyInstances);
        }

        // 优先选择本地数据中心的实例，其次选择远程数据中心的实例
        return findInstanceWithMaxWeight(localInstances)
                .orElse(findInstanceWithMaxWeight(remoteInstances).orElse(null));
    }

    /**
     * 从给定实例列表中找出权重最大的实例。
     * @param instances 实例列表
     * @return 权重最大的实例（Optional包装）
     */
    private static Optional<Instance> findInstanceWithMaxWeight(List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            return Optional.empty();
        }
        return instances.stream().max(java.util.Comparator.comparingDouble(Instance::getWeight));
    }

    /**
     * 连接到指定的服务实例（这里是数据库）并执行操作。
     * @param instance 要连接的服务实例
     */
    public static void runConsumer(Instance instance) {
        java.util.Map<String, String> metadata = instance.getMetadata();
        String dbUrl = metadata.get("db.url");
        String dbUser = metadata.get("db.user");
        String dbPassword = metadata.get("db.password");

        if (dbUrl == null || dbUser == null || dbPassword == null) {
            System.err.println("Database connection information not found in instance metadata.");
            return;
        }

        try {
            // 连接数据库
            connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            System.out.println("Successfully connected to the database at " + dbUrl);
            // 执行一个简单的查询并打印结果
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
                System.out.println("Tables in the database:");
                while (rs.next()) {
                    System.out.println("- " + rs.getString(1));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to connect to or query the database.");
            e.printStackTrace();
        }
    }

    /**
     * 断开当前的数据库连接。
     */
    private static void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("Database connection closed.");
            } catch (Exception e) {
                System.err.println("Failed to close database connection.");
                e.printStackTrace();
            } finally {
                connection = null;
            }
        }
    }
}
