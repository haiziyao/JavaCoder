package com.jcoder.permission;

import com.jcoder.tool.Tool;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class PermissionChecker {
    private volatile PermissionMode mode;
    private final Path projectRoot;

    private final Set<String> allowAlwaysRules = new HashSet<>();

    // 危险命令（演示安全内核，可自行扩充）
    private static final Pattern DANGEROUS = Pattern.compile(
            "(rm\\s+-[a-z]*rf\\s+/)|(mkfs\\.)|(dd\\s+if=.*of=/dev/)|"
                    + "(curl\\s+.*\\|\\s*(ba)?sh)|(wget\\s+.*\\|\\s*(ba)?sh)"
    );

    private static final Map<String, String> TOOL_CHECK_FIELDS = Map.of(
            "Bash",      "command",
            "ReadFile",  "file_path",
            "WriteFile", "file_path",
            "EditFile",  "file_path",
            "Glob",      "pattern",
            "Grep",      "pattern"
    );

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.projectRoot = projectRoot;
    }

    // 记录check_info
    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() { return new CheckResult(PermissionMode.Decision.ALLOW, ""); }
        public static CheckResult deny(String r) { return new CheckResult(PermissionMode.Decision.DENY, r); }
        public static CheckResult ask(String r) { return new CheckResult(PermissionMode.Decision.ASK, r); }
    }


    public CheckResult check(Tool tool,Map<String,Object> args) {

        String toolName = tool.name();
        String content = checkFieldContent(toolName, args);

        // Layer 1: 危险命令直接拒绝
        if("Bash".equals(toolName) && content != null && DANGEROUS.matcher(content).find() ) {
            return CheckResult.deny("dangerous command");
        }
        // Layer 2: 路径沙箱（文件工具必须落在项目根内）
        if (content != null && isPathTool(toolName) && !isPathAllowed(content) && mode != PermissionMode.BYPASS) {
            return CheckResult.ask("path outside project: " + content);
        }
        // Layer 3: 会话级 总是允许
        if (allowAlwaysRules.contains(toolName + ":" + content)) {
            return CheckResult.allow();
        }
        // Layer 4: 模式矩阵兜底
        return switch (mode.decide(tool.category())) {
            case ALLOW -> CheckResult.allow();
            case DENY  -> CheckResult.deny("denied by mode " + mode);
            case ASK   -> CheckResult.ask("");
        };
    }

    public void addAllowAlwaysRule(Tool tool, Map<String, Object> args) {
        allowAlwaysRules.add(tool.name() + ":" + checkFieldContent(tool.name(), args));
    }

    /** 生成给用户看的一句话描述 */
    public String describeToolAction(String toolName, Map<String, Object> args) {
        String content = checkFieldContent(toolName, args);
        return toolName + (content == null ? "" : ": " + content);
    }


    private boolean isPathTool(String name) {
        return "ReadFile".equals(name) || "WriteFile".equals(name) || "EditFile".equals(name);
    }

    private boolean isPathAllowed(String pathStr) {
        if (projectRoot == null) return true;
        try {
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            return p.startsWith(root);
        } catch (Exception e) {
            return true; // 路径非法交给工具自己报错
        }
    }

    private static String checkFieldContent(String toolName, Map<String, Object> args) {
        String field = TOOL_CHECK_FIELDS.get(toolName);
        if (field == null) return null;
        Object v = args.get(field);
        return v instanceof String s ? s : null;
    }

    public PermissionMode cycleMode() {
        mode = switch (mode) {
            case DEFAULT      -> PermissionMode.ACCEPT_EDITS;
            case ACCEPT_EDITS -> PermissionMode.PLAN;
            case PLAN         -> PermissionMode.BYPASS;
            case BYPASS       -> PermissionMode.DEFAULT;
        };
        return mode;
    }

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
}
