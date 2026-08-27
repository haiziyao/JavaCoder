package com.jcoder.skill;

import com.jcoder.context.ContextTokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 管理当前 Agent 会话已经激活的 Skill。
 *
 * SkillCatalog：
 *     磁盘上有哪些可用 Skill。
 *
 * SkillRuntime：
 *     当前会话实际启用了哪些 Skill。
 *
 * active Skill 只保存在运行时，不写入 Conversation。
 */
public final class SkillRuntime {

    /*
     * 防止模型一次激活过多 Skill，
     * 导致每轮请求都重复携带大量 SOP。
     */
    private static final int MAX_ACTIVE_SKILLS = 4;

    /*
     * 所有 active Skill 正文加起来最多约 20K tokens。
     *
     * 使用项目现有的中英文混合估算器，
     * 不再另外实现一套字符换算规则。
     */
    private static final int MAX_ACTIVE_SKILL_TOKENS =
            20_000;

    private final SkillCatalog catalog;

    /*
     * LinkedHashSet 同时满足：
     *
     * 1. 不允许重复激活。
     * 2. 保留激活顺序。
     * 3. Prompt 输出稳定。
     */
    private final Set<String> activeSkillNames =
            new LinkedHashSet<>();

    public SkillRuntime(
            SkillCatalog catalog
    ) {
        this.catalog =
                Objects.requireNonNull(
                        catalog,
                        "catalog"
                );
    }

    /**
     * 激活一个 Skill。
     *
     * 激活只修改运行时状态，不执行 Skill，
     * 也不会在这里直接调用 LLM。
     */
    public synchronized ActivationResult activate(
            String requestedName
    ) {
        removeUnavailableSkills();

        if (requestedName == null
                || requestedName.isBlank()) {
            return ActivationResult.failure(
                    "skill name is required"
            );
        }

        Optional<SkillDefinition> found =
                catalog.find(requestedName);

        if (found.isEmpty()) {
            return ActivationResult.failure(
                    "unknown skill: "
                            + requestedName.strip()
            );
        }

        SkillDefinition definition =
                found.get();

        String canonicalName =
                definition.name();

        if (activeSkillNames.contains(
                canonicalName
        )) {
            return ActivationResult.alreadyActive(
                    canonicalName
            );
        }

        if (activeSkillNames.size()
                >= MAX_ACTIVE_SKILLS) {
            return ActivationResult.failure(
                    "cannot activate skill \""
                            + canonicalName
                            + "\": active skill limit is "
                            + MAX_ACTIVE_SKILLS
            );
        }

        int currentTokens =
                estimateActiveTokens();

        int requestedTokens =
                ContextTokenEstimator.estimateText(
                        definition.body()
                );

        if (currentTokens + requestedTokens
                > MAX_ACTIVE_SKILL_TOKENS) {
            return ActivationResult.failure(
                    "cannot activate skill \""
                            + canonicalName
                            + "\": active Skill SOPs would use about "
                            + (currentTokens + requestedTokens)
                            + " tokens; limit is "
                            + MAX_ACTIVE_SKILL_TOKENS
            );
        }

        activeSkillNames.add(
                canonicalName
        );

        return ActivationResult.activated(
                canonicalName
        );
    }

    /**
     * 从当前上下文停用 Skill。
     *
     * 这不会删除磁盘上的 SKILL.md。
     */
    public synchronized boolean unload(
            String requestedName
    ) {
        if (requestedName == null
                || requestedName.isBlank()) {
            return false;
        }

        String normalized =
                requestedName.strip()
                        .toLowerCase(Locale.ROOT);

        return activeSkillNames.remove(
                normalized
        );
    }

    /**
     * 清空当前会话全部 active Skill。
     *
     * 后续用于：
     * - /clear
     * - /session new
     * - /session resume
     */
    public synchronized int clear() {
        int removed =
                activeSkillNames.size();

        activeSkillNames.clear();

        return removed;
    }

    /**
     * 返回当前有效的 active Skill。
     *
     * 如果 reload 后某个 Skill 已从磁盘删除，
     * 会自动从 active 集合中清理。
     */
    public synchronized List<SkillDefinition> activeSkills() {
        removeUnavailableSkills();

        List<SkillDefinition> definitions =
                new ArrayList<>();

        for (String name : activeSkillNames) {
            catalog.find(name)
                    .ifPresent(
                            definitions::add
                    );
        }

        return List.copyOf(
                definitions
        );
    }

    public synchronized List<String> activeNames() {
        removeUnavailableSkills();

        return List.copyOf(
                activeSkillNames
        );
    }

    public List<SkillDefinition> availableSkills() {
        return catalog.list();
    }

    public List<String> catalogErrors() {
        return catalog.loadErrors();
    }

    /**
     * 给模型看的轻量 Skill 目录。
     *
     *这里只放 name 和 description，
     * 不放完整 SOP。
     */
    public String renderAvailableSkills() {
        List<SkillDefinition> available =
                catalog.list();

        if (available.isEmpty()) {
            return "";
        }

        StringBuilder output =
                new StringBuilder();

        output.append(
                "## Available Skills\n\n"
        );

        output.append(
                "Only Skill names and descriptions are shown here. "
        );

        output.append(
                "When the current task matches a Skill, call "
        );

        output.append(
                "LoadSkill with its exact name before continuing.\n\n"
        );

        for (SkillDefinition definition :
                available) {

            output
                    .append("- ")
                    .append(definition.name())
                    .append(": ")
                    .append(
                            definition.metadata()
                                    .description()
                    )
                    .append('\n');
        }

        return output.toString()
                .strip();
    }

    /**
     * 把当前 active Skill 渲染为完整 Prompt。
     *
     * 这个字符串之后会作为临时 system message
     * 放入 PromptContent，不写入 Conversation。
     */
    public synchronized String renderActiveSkills() {
        List<SkillDefinition> active =
                activeSkills();

        if (active.isEmpty()) {
            return "";
        }

        StringBuilder output =
                new StringBuilder();

        output.append(
                "<active-skills>\n"
        );

        output.append(
                "The following Skills are active for the current session. "
        );

        output.append(
                "Treat them as task-specific procedures. "
        );

        output.append(
                "They do not override system rules, security rules, "
        );

        output.append(
                "permission checks, or the user's requested scope.\n"
        );

        for (SkillDefinition definition :
                active) {

            output
                    .append("\n## Skill: ")
                    .append(definition.name())
                    .append("\n\n")
                    .append(definition.body())
                    .append('\n');
        }

        output.append(
                "</active-skills>"
        );

        return output.toString();
    }

    public SkillCatalog.ReloadReport reloadCatalog() {
        SkillCatalog.ReloadReport report =
                catalog.reload();

        /*
         * reload 后可能有 Skill 被删除。
         * 立即移除已经失效的 active name。
         */
        synchronized (this) {
            removeUnavailableSkills();
        }

        return report;
    }

    private int estimateActiveTokens() {
        int total = 0;

        for (String name : activeSkillNames) {
            Optional<SkillDefinition> definition =
                    catalog.find(name);

            if (definition.isPresent()) {
                total +=
                        ContextTokenEstimator.estimateText(
                                definition.get().body()
                        );
            }
        }

        return total;
    }

    private void removeUnavailableSkills() {
        activeSkillNames.removeIf(
                name -> catalog.find(name).isEmpty()
        );
    }

    public record ActivationResult(
            boolean success,
            boolean changed,
            String skillName,
            String message
    ) {
        public ActivationResult {
            skillName =
                    skillName == null
                            ? ""
                            : skillName;

            message =
                    message == null
                            ? ""
                            : message;
        }

        public static ActivationResult activated(
                String name
        ) {
            return new ActivationResult(
                    true,
                    true,
                    name,
                    "Skill activated: " + name
            );
        }

        public static ActivationResult alreadyActive(
                String name
        ) {
            return new ActivationResult(
                    true,
                    false,
                    name,
                    "Skill is already active: " + name
            );
        }

        public static ActivationResult failure(
                String message
        ) {
            return new ActivationResult(
                    false,
                    false,
                    "",
                    message
            );
        }
    }
}