package com.ospx.sharedcampaignresearch.domain;

import com.ospx.sharedcampaignresearch.SharedCampaignResearchMod;
import java.util.Objects;
import mindustry.Vars;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Call;
import mindustry.type.ItemStack;

public final class ResearchApplyService {

    private final ResearchRules rules;
    private final ResearchRequestValidator validator;
    private final ResearchDialogAccess dialogAccess;

    public ResearchApplyService(
        ResearchRules rules,
        ResearchRequestValidator validator,
        ResearchDialogAccess dialogAccess
    ) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.dialogAccess = Objects.requireNonNull(dialogAccess, "dialogAccess");
    }

    public TechNode findRequestedNode(String nodeName) {
        return validator.findRequestedNode(nodeName);
    }

    public boolean shouldApplyResearch(String nodeName) {
        TechNode node = findRequestedNode(nodeName);
        return node != null
            && rules.requiresHostAuthority()
            && validator.canApplyHostSide(nodeName);
    }

    public ApplyResearchResult applyResearch(String nodeName) {
        TechNode node = findRequestedNode(nodeName);
        if (node == null || !shouldApplyResearch(nodeName)) {
            SharedCampaignResearchMod.log("apply rejected: node=" + nodeName);
            return ApplyResearchResult.rejected();
        }

        SharedCampaignResearchMod.log("apply start: node=" + node.content.name);
        TechNode rootNode = rules.getResearchRoot(node);
        ApplyResearchResult result = dialogAccess.withDialogState(rootNode, dialog -> {
            if (dialog.items == null) {
                SharedCampaignResearchMod.log("apply unavailable: node=" + node.content.name + ", reason=dialog-items-null");
                return ApplyResearchResult.unavailable();
            }

            boolean complete = true;

            for (int index = 0; index < node.requirements.length; index++) {
                ItemStack requirement = node.requirements[index];
                ItemStack completed = node.finishedRequirements[index];
                int missing = requirement.amount - completed.amount;
                int used = Math.max(Math.min(missing, dialog.items.get(requirement.item)), 0);
                dialog.items.remove(requirement.item, used);
                completed.amount += used;

                if (completed.amount < requirement.amount) {
                    complete = false;
                }
            }

            if (complete) {
                unlock(node);
            }

            node.save();
            return complete ? ApplyResearchResult.completed() : ApplyResearchResult.progressed();
        });

        ApplyResearchResult finalResult = result == null ? ApplyResearchResult.unavailable() : result;
        SharedCampaignResearchMod.log("apply result: node=" + node.content.name + ", result=" + finalResult);
        return finalResult;
    }

    private void unlock(TechNode node) {
        unlockContent(node.content);

        TechNode parent = node.parent;
        while (parent != null) {
            unlockContent(parent.content);
            parent = parent.parent;
        }
    }

    private void unlockContent(UnlockableContent content) {
        content.unlock();
        if (Vars.state != null && Vars.state.rules != null) {
            Vars.state.rules.researched.add(content);
        }
        Call.researched(content);
    }

    public enum ApplyResearchResult {
        REJECTED,
        UNAVAILABLE,
        PROGRESSED,
        COMPLETED;

        public static ApplyResearchResult rejected() {
            return REJECTED;
        }

        public static ApplyResearchResult unavailable() {
            return UNAVAILABLE;
        }

        public static ApplyResearchResult progressed() {
            return PROGRESSED;
        }

        public static ApplyResearchResult completed() {
            return COMPLETED;
        }

        public boolean isSuccess() {
            return this == PROGRESSED || this == COMPLETED;
        }
    }
}
