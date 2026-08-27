package com.jcoder.tool.impl;

import com.jcoder.skill.SkillRuntime;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolArgsHelper;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模型用于激活 Skill 的适配工具。
 *
 * 它不返回完整 SOP，避免 SOP 同时出现在：
 *
 * 1. ToolResult
 * 2. active Skill system reminder
 *
 * 完整 SOP 只由下一轮 PromptBuilder 注入一次。
 */
public final class LoadSkillTool implements Tool {

    private final SkillRuntime skillRuntime;

    public LoadSkillTool(
            SkillRuntime skillRuntime
    ) {
        this.skillRuntime =
                Objects.requireNonNull(
                        skillRuntime,
                        "skillRuntime"
                );
    }

    @Override
    public String name() {
        return "LoadSkill";
    }

    @Override
    public String description() {
        return """
                Activate an available Skill for the current session. \
                Call this when the user's task matches one of the Skills \
                listed in the Available Skills section. The Skill's full \
                SOP will be injected into the next model request. Pass the \
                exact Skill name without a leading slash.
                """.strip();
    }

    @Override
    public ToolCategory category() {
        /*
         * 只读取 Catalog 并修改进程内运行状态，
         * 不写磁盘，所以归入 READ。
         */
        return ToolCategory.READ;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "name",
                        new ToolParamDefinition(
                                "string",
                                "要激活的 Skill 名称，例如 java-review",
                                Map.of()
                        )
                ),
                List.of("name"),
                new ToolReturnDefinition(
                        "string",
                        Map.of()
                )
        );
    }

    @Override
    public ToolExecuteResult execute(
            Map<String, Object> args
    ) {
        String requestedName =
                ToolArgsHelper.stringArg(
                        args,
                        "name",
                        ""
                );

        if (requestedName.isBlank()) {
            return ToolExecuteResult.error(
                    "Error: name is required"
            );
        }

        SkillRuntime.ActivationResult result =
                skillRuntime.activate(
                        requestedName
                );

        if (!result.success()) {
            return ToolExecuteResult.error(
                    "Error: " + result.message()
            );
        }

        if (!result.changed()) {
            return ToolExecuteResult.success(
                    result.message()
                            + ". Its SOP remains available "
                            + "in the current session context."
            );
        }

        return ToolExecuteResult.success(
                result.message()
                        + ". The full SOP will be injected "
                        + "into the next model request."
        );
    }
}