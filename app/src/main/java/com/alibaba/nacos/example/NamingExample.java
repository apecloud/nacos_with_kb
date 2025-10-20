package com.alibaba.nacos.example;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.apache.commons.cli.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 服务注册与注销示例类。
 * 该类可以通过命令行参数来注册或注销一个服务实例。
 */
public class NamingExample {

    public static void main(String[] args) throws NacosException {
        // 定义命令行参数
        Options options = new Options();
        options.addOption("s", "serverAddr", true, "Nacos server address");
        options.addOption("n", "namespace", true, "Nacos namespace");
        options.addOption("sn", "serviceName", true, "Service name to register");
        options.addOption("d", "deregister", false, "Deregister service instance");
        options.addOption("i", "ip", true, "Service instance IP");
        options.addOption("p", "port", true, "Service instance port");
        options.addOption("w", "weight", true, "Service instance weight");
        options.addOption(null, "db.url", true, "Database URL");
        options.addOption(null, "db.user", true, "Database user");
        options.addOption(null, "db.password", true, "Database password");
        options.addOption("dc", "datacenter", true, "Datacenter");
        options.addOption("h", "help", false, "Print this message");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            // 解析命令行参数
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("NamingExample", options);
            System.exit(1);
            return;
        }

        // 如果用户请求帮助信息，则打印并退出
        if (cmd.hasOption("help")) {
            formatter.printHelp("NamingExample", options);
            System.out.println("\nExample for registration:");
            System.out.println("NamingExample -s <serverAddr> -n <namespace> -sn <serviceName> -p <port> -w <weight> -dc <datacenter> --db.url <db.url> --db.user <db.user> --db.password <db.password>");
            System.out.println("\nExample for deregistration:");
            System.out.println("NamingExample -s <serverAddr> -n <namespace> -sn <serviceName> -d -i <ip> -p <port>");
            System.exit(0);
        }

        // 获取命令行参数值，如果未提供则使用默认值
        String serverAddr = cmd.getOptionValue("serverAddr", "127.0.0.1:8848");
        String namespace = cmd.getOptionValue("namespace", "public");
        String serviceName = cmd.getOptionValue("serviceName", "dr.mysql");
        String ip = cmd.getOptionValue("ip");
        int port = Integer.parseInt(cmd.getOptionValue("port", "3306"));
        double weight = Double.parseDouble(cmd.getOptionValue("weight", "1.0"));

        // 校验权重值范围
        if (weight < 0 || weight > 100) {
            System.err.println("Weight must be between 0 and 100.");
            formatter.printHelp("NamingExample", options);
            System.exit(1);
        }

        // 设置Nacos连接属性
        Properties properties = new Properties();
        properties.setProperty("serverAddr", serverAddr);
        properties.setProperty("namespace", namespace);

        // 创建Nacos命名服务客户端
        NamingService naming = NamingFactory.createNamingService(properties);

        // 如果是注销操作
        if (cmd.hasOption("deregister")) {
            if (ip == null) {
                System.err.println("Service instance IP is required for deregistration.");
                formatter.printHelp("NamingExample", options);
                System.exit(1);
            }
            try {
                Instance instance = new Instance();
                instance.setIp(ip);
                instance.setPort(port);
                instance.setEphemeral(false); // 注销持久化实例
                naming.deregisterInstance(serviceName, instance);
                System.out.println("Service instance " + ip + ":" + port + " deregistered successfully from " + serviceName);
            } catch (NacosException e) {
                System.err.println("Failed to deregister service instance.");
                e.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
        }

        // 如果未提供IP，则自动获取本机IP
        if (ip == null) {
            try {
                ip = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }

        // 创建服务实例对象
        String datacenter = cmd.getOptionValue("datacenter", "default");
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setHealthy(true);
        instance.setWeight(weight);
        instance.setEphemeral(false); // 注册为持久化实例

        // 设置实例的元数据
        Map<String, String> metadata = new HashMap<>();
        String dbUrl = cmd.getOptionValue("db.url", "jdbc:mysql://" + ip + ":" + port + "/test");
        String dbUser = cmd.getOptionValue("db.user", "root");
        String dbPassword = cmd.getOptionValue("db.password", "123456");
        metadata.put("db.url", dbUrl);
        metadata.put("db.user", dbUser);
        metadata.put("db.password", dbPassword);
        metadata.put("datacenter", datacenter);
        instance.setMetadata(metadata);

        try {
            // 注册实例
            naming.registerInstance(serviceName, instance);
            System.out.println("Service registered successfully: " + serviceName + " at " + ip + ":" + port + " in datacenter " + datacenter);
        } catch (NacosException e) {
            System.err.println("Failed to register service.");
            e.printStackTrace();
            System.exit(1);
        }

        // 保持程序运行，直到被手动终止
        System.out.println("Service registered. Press Ctrl+C to exit.");

        // 阻塞主线程
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }
    }
}
