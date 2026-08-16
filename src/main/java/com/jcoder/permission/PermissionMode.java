package com.jcoder.permission;

import com.jcoder.tool.ToolCategory;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    public Decision decide(ToolCategory category) {
            return switch (this){
                case DEFAULT -> switch (category) {
                    case READ -> Decision.ALLOW;          // 读直接放行
                    case WRITE, COMMAND -> Decision.ASK;  // 写/命令要问
                };
                case ACCEPT_EDITS -> switch (category) {
                    case READ, WRITE -> Decision.ALLOW;
                    case COMMAND -> Decision.ASK;
                };
                case PLAN -> DEFAULT.decide(category);    // 规划模式先按默认
                case BYPASS -> Decision.ALLOW;
            };
    }

    public enum Decision {
        ALLOW,
        ASK,
        DENY
    }

}
