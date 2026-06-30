/**
 * @author melon
 */
package com.melon.core.security;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能安全扫描结果数据模型.
 * Corresponds to Python ScanResult.
 */
public class ScanResult {

    private String skillName;
    private boolean safe;
    private final List<String> issues = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public ScanResult() {}

    public ScanResult(String skillName) {
        this.skillName = skillName;
        this.safe = true;
    }

    /**
     * 添加一个问题 (严重, 会导致 safe=false).
     */
    public void addIssue(String issue) {
        this.issues.add(issue);
        this.safe = false;
    }

    /**
     * 添加一个警告 (不阻止加载, 但提示用户).
     */
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public boolean isSafe() {
        return safe;
    }

    public void setSafe(boolean safe) {
        this.safe = safe;
    }

    public List<String> getIssues() {
        return issues;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "ScanResult{skillName='" + skillName + "', safe=" + safe
                + ", issues=" + issues.size() + ", warnings=" + warnings.size() + "}";
    }
}
