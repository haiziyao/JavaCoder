package com.jcoder;

import com.jcoder.run.AgentEventListener;
import com.jcoder.run.CodingAgent;
import com.jcoder.ui.WebServer;

import java.util.Map;
import java.util.Scanner;

/** MyCoder 启动入口：默认 Web UI，传入 --cli 可使用命令行。 */
public class Main {
    public static void main(String[] args) {
        try {
            if (args.length > 0 && "--cli".equalsIgnoreCase(args[0])) {
                runCli();
            } else {
                int port = readPort(args);
                new WebServer(port).start(true);
            }
        } catch (Exception e) {
            System.err.println("MyCoder 启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int readPort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) return Integer.parseInt(args[i + 1]);
        }
        return 8080;
    }

    private static void runCli() {
        CodingAgent agent = new CodingAgent();
        AgentEventListener printer = new AgentEventListener() {
            public void onContent(String delta) { System.out.print(delta); System.out.flush(); }
            public void onToolStart(String name, Map<String, Object> args) {
                System.err.println("\n[tool] " + name + " " + args);
            }
            public void onToolEnd(String name, String output, boolean error) {
                System.err.println("[tool] " + name + (error ? " failed" : " finished"));
            }
        };
        System.out.println("MyCoder CLI 已启动，输入 exit 或 quit 退出。\n");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) break;
                if (input.isBlank()) continue;
                try { agent.chat(input, printer); System.out.println(); }
                catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
            }
        }
    }
}
