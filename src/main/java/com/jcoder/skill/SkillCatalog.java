package com.jcoder.skill;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 负责发现和加载 Skill。
 *
 * 加载顺序：
 *
 * 用户级 ~/.mycoder/skills
 *     ↓
 * 项目级 <project>/.mycoder/skills
 *
 * 后加载的项目级同名 Skill 覆盖用户级 Skill。
 */
public final class SkillCatalog {

    /*
     * 防止意外加载特别大的 SKILL.md。
     *
     * Skill 最终会进入模型上下文，因此即使磁盘允许很大的文件，
     * Harness 也应该在发现阶段设置明确上限。
     */
    private static final long MAX_SKILL_FILE_BYTES =
            256L * 1024L;

    private final Path userSkillsRoot;
    private final Path projectSkillsRoot;

    /*
     * 使用不可变快照。
     *
     * reload 构造一份新 Map 后整体替换，
     * 读取线程不会观察到“只加载了一半”的 Catalog。
     */
    private volatile Map<String, SkillDefinition> skills =
            Map.of();

    private volatile List<String> loadErrors =
            List.of();

    public SkillCatalog(
            Path userSkillsRoot,
            Path projectSkillsRoot
    ) {
        if (userSkillsRoot == null) {
            throw new IllegalArgumentException(
                    "userSkillsRoot is required"
            );
        }

        if (projectSkillsRoot == null) {
            throw new IllegalArgumentException(
                    "projectSkillsRoot is required"
            );
        }

        this.userSkillsRoot =
                userSkillsRoot
                        .toAbsolutePath()
                        .normalize();

        this.projectSkillsRoot =
                projectSkillsRoot
                        .toAbsolutePath()
                        .normalize();
    }

    /**
     * 根据项目根目录创建并立即加载 Catalog。
     */
    public static SkillCatalog load(
            Path projectRoot
    ) {
        if (projectRoot == null) {
            throw new IllegalArgumentException(
                    "projectRoot is required"
            );
        }

        String userHome =
                System.getProperty(
                        "user.home",
                        "."
                );

        Path userRoot =
                Path.of(
                        userHome,
                        ".mycoder",
                        "skills"
                );

        Path projectRootNormalized =
                projectRoot
                        .toAbsolutePath()
                        .normalize();

        Path projectSkills =
                projectRootNormalized.resolve(
                        Path.of(
                                ".mycoder",
                                "skills"
                        )
                );

        SkillCatalog catalog =
                new SkillCatalog(
                        userRoot,
                        projectSkills
                );

        catalog.reload();
        return catalog;
    }

    /**
     * 重新扫描两个目录。
     *
     * 单个 Skill 损坏不会导致整个 Catalog 不可用。
     * 错误会进入 ReloadReport 和 loadErrors。
     */
    public synchronized ReloadReport reload() {
        Map<String, SkillDefinition> discovered =
                new LinkedHashMap<>();

        List<String> errors =
                new ArrayList<>();

        int loadedDefinitions = 0;

        loadedDefinitions += loadTier(
                userSkillsRoot,
                SkillDefinition.Source.USER,
                discovered,
                errors
        );

        /*
         * 项目级最后加载。
         * Map.put 会覆盖用户级同名 Skill。
         */
        loadedDefinitions += loadTier(
                projectSkillsRoot,
                SkillDefinition.Source.PROJECT,
                discovered,
                errors
        );

        /*
         * 排序使 Prompt、命令输出和测试稳定。
         */
        Map<String, SkillDefinition> sorted =
                new LinkedHashMap<>();

        discovered.entrySet()
                .stream()
                .sorted(
                        Map.Entry.comparingByKey()
                )
                .forEach(entry ->
                        sorted.put(
                                entry.getKey(),
                                entry.getValue()
                        )
                );

        skills = Collections.unmodifiableMap(
                new LinkedHashMap<>(sorted)
        );

        loadErrors = List.copyOf(errors);

        return new ReloadReport(
                loadedDefinitions,
                skills.size(),
                loadErrors
        );
    }

    /**
     * 按名称查找 Skill。
     *
     * 用户或模型传入 Java-Review 时，
     * 会规范化为 java-review。
     */
    public Optional<SkillDefinition> find(
            String name
    ) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        String normalized =
                name.strip()
                        .toLowerCase(Locale.ROOT);

        return Optional.ofNullable(
                skills.get(normalized)
        );
    }

    public List<SkillDefinition> list() {
        return List.copyOf(
                skills.values()
        );
    }

    public List<String> loadErrors() {
        return loadErrors;
    }

    public int size() {
        return skills.size();
    }

    public Path userSkillsRoot() {
        return userSkillsRoot;
    }

    public Path projectSkillsRoot() {
        return projectSkillsRoot;
    }

    private int loadTier(
            Path root,
            SkillDefinition.Source source,
            Map<String, SkillDefinition> target,
            List<String> errors
    ) {
        /*
         * 没有该目录表示这一层没有安装 Skill，
         * 属于正常情况。
         */
        if (!Files.isDirectory(
                root,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return 0;
        }

        List<Path> directories;

        try (Stream<Path> entries =
                     Files.list(root)) {

            directories = entries
                    .filter(path ->
                            Files.isDirectory(
                                    path,
                                    LinkOption.NOFOLLOW_LINKS
                            )
                    )
                    .sorted(
                            Comparator.comparing(path ->
                                    path.getFileName()
                                            .toString()
                            )
                    )
                    .toList();

        } catch (IOException error) {
            errors.add(
                    "cannot list skill directory "
                            + root
                            + ": "
                            + error.getMessage()
            );
            return 0;
        }

        int loaded = 0;

        for (Path directory : directories) {
            try {
                SkillDefinition definition =
                        loadSkill(
                                directory,
                                source
                        );

                target.put(
                        definition.name(),
                        definition
                );

                loaded++;

            } catch (Exception error) {
                errors.add(
                        "cannot load skill "
                                + directory
                                + ": "
                                + error.getMessage()
                );
            }
        }

        return loaded;
    }

    private SkillDefinition loadSkill(
            Path directory,
            SkillDefinition.Source source
    ) throws IOException {

        Path manifest =
                directory.resolve("SKILL.md");

        if (!Files.isRegularFile(
                manifest,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw new IOException(
                    "SKILL.md is missing"
            );
        }

        long fileSize =
                Files.size(manifest);

        if (fileSize > MAX_SKILL_FILE_BYTES) {
            throw new IOException(
                    "SKILL.md is too large: "
                            + fileSize
                            + " bytes"
            );
        }

        String content =
                Files.readString(
                        manifest,
                        StandardCharsets.UTF_8
                );

        ParsedSkill parsed =
                parseSkillFile(content);

        /*
         * 第一版要求目录名与 frontmatter name 完全一致。
         *
         * 避免磁盘上叫 java-review，
         * Catalog 里却注册成 unrelated-skill。
         */
        String directoryName =
                directory.getFileName()
                        .toString();

        if (!directoryName.equals(
                parsed.metadata().name()
        )) {
            throw new IOException(
                    "directory name \""
                            + directoryName
                            + "\" does not match skill name \""
                            + parsed.metadata().name()
                            + "\""
            );
        }

        return new SkillDefinition(
                parsed.metadata(),
                parsed.body(),
                directory,
                source
        );
    }

    private ParsedSkill parseSkillFile(
            String originalContent
    ) throws IOException {

        if (originalContent == null
                || originalContent.isBlank()) {
            throw new IOException(
                    "SKILL.md is empty"
            );
        }

        String content = originalContent;

        /*
         * UTF-8 文件偶尔带 BOM，
         * 去掉后再检查第一行。
         */
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        /*
         * 统一 Windows/Linux 换行，
         * 后面的 frontmatter 分隔符判断更稳定。
         */
        content = content
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] lines =
                content.split(
                        "\n",
                        -1
                );

        if (lines.length == 0
                || !"---".equals(
                        lines[0].strip()
                )) {
            throw new IOException(
                    "SKILL.md must start with YAML frontmatter"
            );
        }

        int closingDelimiter = -1;

        for (int index = 1;
             index < lines.length;
             index++) {

            if ("---".equals(
                    lines[index].strip()
            )) {
                closingDelimiter = index;
                break;
            }
        }

        if (closingDelimiter < 0) {
            throw new IOException(
                    "YAML frontmatter closing delimiter is missing"
            );
        }

        String yamlText =
                String.join(
                        "\n",
                        Arrays.copyOfRange(
                                lines,
                                1,
                                closingDelimiter
                        )
                );

        String body =
                String.join(
                        "\n",
                        Arrays.copyOfRange(
                                lines,
                                closingDelimiter + 1,
                                lines.length
                        )
                ).strip();

        if (body.isBlank()) {
            throw new IOException(
                    "skill SOP body is empty"
            );
        }

        Map<?, ?> metadataMap =
                parseYaml(yamlText);

        String name =
                requiredScalar(
                        metadataMap,
                        "name"
                );

        String description =
                requiredScalar(
                        metadataMap,
                        "description"
                );

        SkillMetadata metadata;

        try {
            metadata =
                    new SkillMetadata(
                            name,
                            description
                    );
        } catch (IllegalArgumentException error) {
            throw new IOException(
                    error.getMessage(),
                    error
            );
        }

        return new ParsedSkill(
                metadata,
                body
        );
    }

    private Map<?, ?> parseYaml(
            String yamlText
    ) throws IOException {

        try {
            LoaderOptions options =
                    new LoaderOptions();

            /*
             * SafeConstructor 不允许 YAML 创建任意 Java 对象，
             * 这里只需要普通 Map/List/字符串。
             */
            Yaml yaml =
                    new Yaml(
                            new SafeConstructor(options)
                    );

            Object parsed =
                    yaml.load(yamlText);

            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException(
                        "YAML frontmatter must be an object"
                );
            }

            return map;

        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(
                    "invalid YAML frontmatter: "
                            + error.getMessage(),
                    error
            );
        }
    }

    private String requiredScalar(
            Map<?, ?> map,
            String field
    ) throws IOException {

        Object value =
                map.get(field);

        if (value == null
                || value.toString().isBlank()) {
            throw new IOException(
                    "frontmatter field \""
                            + field
                            + "\" is required"
            );
        }

        return value.toString()
                .strip();
    }

    private record ParsedSkill(
            SkillMetadata metadata,
            String body
    ) {
    }

    public record ReloadReport(
            int loadedDefinitions,
            int availableSkills,
            List<String> errors
    ) {
        public ReloadReport {
            if (loadedDefinitions < 0
                    || availableSkills < 0) {
                throw new IllegalArgumentException(
                        "skill counts must not be negative"
                );
            }

            errors = errors == null
                    ? List.of()
                    : List.copyOf(errors);
        }

        public boolean successful() {
            return errors.isEmpty();
        }
    }
}