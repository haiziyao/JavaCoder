package com.jcoder.tool;

import com.jcoder.tool.impl.BashTool;
import com.jcoder.tool.impl.EditFileTool;
import com.jcoder.tool.impl.GlobTool;
import com.jcoder.tool.impl.GrepTool;
import com.jcoder.tool.impl.ReadFileTool;
import com.jcoder.tool.impl.WriteFileTool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class ToolRegister {

    private final Map<String,Tool> tools = new ConcurrentHashMap<>();

    private final Set<String> discoveredTools = ConcurrentHashMap.newKeySet();

    public void register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("tool and tool name are required");
        }
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public List<Tool> listTools() {
        return List.copyOf(tools.values());
    }

    public List<ToolDefinition> listDefinitions() {
        return tools.values().stream()
                .map(Tool::definition)
                .toList();
    }

    public void markDiscovered(String name) {
        if (name != null && !name.isBlank()) {
            discoveredTools.add(name);
        }
    }

    public boolean isDiscovered(String name) {
        return name != null && discoveredTools.contains(name);
    }

    public static ToolRegister createDefault() {
        ToolRegister register = new ToolRegister();
        register.register(new ReadFileTool());
        register.register(new WriteFileTool());
        register.register(new EditFileTool());
        register.register(new BashTool());
        register.register(new GlobTool());
        register.register(new GrepTool());
        return register;
    }

}
