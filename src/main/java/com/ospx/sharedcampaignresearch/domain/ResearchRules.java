package com.ospx.sharedcampaignresearch.domain;

import arc.struct.Seq;
import com.ospx.sharedcampaignresearch.util.StringsCompat;
import java.util.Objects;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.game.Objectives.Objective;
import mindustry.type.ItemSeq;

public final class ResearchRules {

    private final ResearchDialogAccess dialogAccess;

    public ResearchRules(ResearchDialogAccess dialogAccess) {
        this.dialogAccess = Objects.requireNonNull(dialogAccess, "dialogAccess");
    }

    public boolean isClientSideUnlockAllowed() {
        return false;
    }

    public boolean requiresHostAuthority() {
        return true;
    }

    public TechNode findNodeByContentName(String contentName) {
        if (StringsCompat.isBlank(contentName)) {
            return null;
        }

        for (TechNode node : TechTree.all) {
            if (node.content != null && Objects.equals(node.content.name, contentName)) {
                return node;
            }
        }

        return null;
    }

    public TechNode getResearchRoot(TechNode node) {
        TechNode current = node;
        while (current != null && current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    public boolean hasIncompleteObjectives(TechNode node) {
        if (node == null) {
            return true;
        }

        Seq<Objective> objectives = node.objectives;
        for (Objective objective : objectives) {
            if (!objective.complete()) {
                return true;
            }
        }

        return false;
    }

    public boolean areParentsUnlocked(TechNode node) {
        TechNode parent = node == null ? null : node.parent;
        while (parent != null) {
            if (!parent.content.unlockedHost()) {
                return false;
            }
            parent = parent.parent;
        }
        return true;
    }

    public boolean isCampaignSession() {
        return Vars.state != null
            && Vars.state.isCampaign()
            && Vars.state.rules != null
            && Vars.state.rules.sector != null;
    }

    public boolean isClientCampaign() {
        return Vars.net != null && Vars.net.client() && isCampaignSession();
    }

    public boolean isHostCampaign() {
        return Vars.net != null && !Vars.net.client() && isCampaignSession();
    }

    public boolean isNodeLocked(TechNode node) {
        return node != null && node.content != null && !node.content.unlockedHost();
    }

    public boolean isSelectable(TechNode node) {
        return node != null && (node.content.unlockedHost() || !hasIncompleteObjectives(node));
    }

    public boolean canSpend(TechNode node) {
        if (node == null || !isSelectable(node) || !isHostCampaign()) {
            return false;
        }

        TechNode rootNode = getResearchRoot(node);
        ItemSeq items = dialogAccess.copyItemsForRoot(rootNode);
        if (items == null) {
            return false;
        }

        if (node.requirements.length == 0) {
            return true;
        }

        for (int index = 0; index < node.requirements.length; index++) {
            if (node.finishedRequirements[index].amount < node.requirements[index].amount
                && items.has(node.requirements[index].item)) {
                return true;
            }
        }

        return node.content.locked();
    }

    public boolean canOfferRequestButton(TechNode node) {
        return isClientCampaign()
            && node != null
            && isNodeLocked(node)
            && areParentsUnlocked(node);
    }
}
