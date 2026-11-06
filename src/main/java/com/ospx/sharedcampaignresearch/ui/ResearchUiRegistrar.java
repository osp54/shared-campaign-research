package com.ospx.sharedcampaignresearch.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import com.ospx.sharedcampaignresearch.SharedCampaignResearchMod;
import com.ospx.sharedcampaignresearch.domain.ResearchRequestValidator;
import com.ospx.sharedcampaignresearch.net.NetworkRegistrar;
import com.ospx.sharedcampaignresearch.sync.ClientResearchState;
import com.ospx.sharedcampaignresearch.sync.ResearchItemSnapshot;
import com.ospx.sharedcampaignresearch.sync.ResearchProgressSnapshot;
import java.util.Objects;
import mindustry.Vars;
import mindustry.content.TechTree.TechNode;
import mindustry.game.EventType;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.ResearchDialog;

public final class ResearchUiRegistrar {

    private static final String REQUEST_BUTTON_NAME = "shared-campaign-research-button";
    private static final String CLIENT_INFO_NAME = "shared-campaign-research-info";
    private static final String REQUEST_BUNDLE_KEY = "sharedcampaignresearch.ui.request";
    private static final String PROGRESS_BUNDLE_KEY = "sharedcampaignresearch.ui.progress";
    private static final String AMOUNT_BUNDLE_KEY = "sharedcampaignresearch.ui.amount";

    private final ClientResearchState clientResearchState;
    private final ResearchRequestValidator validator;
    private final NetworkRegistrar networkRegistrar;
    private boolean dialogWasShown;
    private TechNode lastRootNode;
    private TechNode lastHoveredNode;
    private String lastInfoSignature;
    private boolean lastRequestVisible;
    private String lastTotalsSignature;

    public ResearchUiRegistrar(
        ClientResearchState clientResearchState,
        ResearchRequestValidator validator,
        NetworkRegistrar networkRegistrar
    ) {
        this.clientResearchState = Objects.requireNonNull(clientResearchState, "clientResearchState");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.networkRegistrar = Objects.requireNonNull(networkRegistrar, "networkRegistrar");
    }

    public void registerClientUiHooks() {
        SharedCampaignResearchMod.log("ui registrar initialized");
        Events.run(EventType.Trigger.update, this::syncResearchUi);
    }

    public boolean shouldShowRequestButton(String nodeName) {
        return validator.canClientRequestResearch(nodeName);
    }

    public void requestResearch(String rootName, String nodeName) {
        clientResearchState.setLastRequestedRoot(rootName);
        SharedCampaignResearchMod.log("ui request click: root=" + rootName + ", node=" + nodeName);
        networkRegistrar.requestResearch(nodeName);
    }

    private void syncResearchUi() {
        if (Vars.ui == null || Vars.ui.research == null || !Vars.net.client()) {
            dialogWasShown = false;
            return;
        }

        ResearchDialog dialog = Vars.ui.research;
        if (!dialog.isShown() || dialog.view == null || dialog.view.infoTable == null) {
            dialogWasShown = false;
            return;
        }

        if (!dialogWasShown) {
            lastTotalsSignature = null;
            dialogWasShown = true;
        }

        ensureClientSnapshot(dialog.lastNode);
        syncClientSelectability(dialog);
        installNodeRequestListeners(dialog);
        syncClientTotals(dialog, dialog.lastNode);
        TechNode hoveredNode = dialog.view.hoverNode == null ? null : (TechNode) dialog.view.hoverNode.userObject;
        if (shouldRefreshInfo(dialog.lastNode, hoveredNode)) {
            syncInfoLabel(dialog.view.infoTable, dialog.lastNode, hoveredNode);
            syncRequestButton(dialog.view.infoTable, dialog.lastNode, hoveredNode);
            refreshInfoTableLayout(dialog.view.infoTable);
            lastRootNode = dialog.lastNode;
            lastHoveredNode = hoveredNode;
        }
    }

    private void syncClientSelectability(ResearchDialog dialog) {
        for (ResearchDialog.TechTreeNode treeNode : dialog.nodes) {
            if (treeNode.node.content != null
                && validator.canClientRequestResearch(treeNode.node.content.name)) {
                treeNode.selectable = true;
            }
        }
    }

    private void installNodeRequestListeners(ResearchDialog dialog) {
        for (Element child : dialog.view.getChildren()) {
            if (!(child instanceof ImageButton button) || !(button.userObject instanceof TechNode node)) {
                continue;
            }
            if ("shared-campaign-research-node".equals(button.name)) {
                continue;
            }

            button.name = "shared-campaign-research-node";
            button.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (node.content != null && validator.canClientRequestResearch(node.content.name)) {
                        requestResearch(rootName(dialog.lastNode), node.content.name);
                    }
                }
            });
        }
    }

    private void ensureClientSnapshot(TechNode rootNode) {
        if (rootNode == null || rootNode.content == null) {
            return;
        }

        String rootName = rootNode.content.name;
        if (clientResearchState.itemSnapshotsByRoot().containsKey(rootName)) {
            return;
        }

        SharedCampaignResearchMod.log("ui initial snapshot request: root=" + rootName);
        networkRegistrar.requestInitialSync(rootName);
    }

    private void syncInfoLabel(Table infoTable, TechNode rootNode, TechNode hoveredNode) {
        Element existing = infoTable.find(CLIENT_INFO_NAME);
        if (existing != null) {
            existing.remove();
        }

        ResearchItemSnapshot snapshot = getSnapshot(rootNode);
        ResearchProgressSnapshot progress = getProgressSnapshot(hoveredNode);
        if ((snapshot == null || snapshot.items().isEmpty()) && progress == null) {
            return;
        }

        if (hoveredNode == null || hoveredNode.content == null) {
            return;
        }

        Table wrapper = new Table();
        wrapper.name = CLIENT_INFO_NAME;
        wrapper.left();
        wrapper.defaults().left().fillX();

        addProgressRow(wrapper, hoveredNode, progress);
        addRequirementRows(wrapper, hoveredNode, progress, snapshot);

        infoTable.row();
        infoTable.add(wrapper).left().growX().padTop(4f);
        lastInfoSignature = buildInfoSignature(snapshot, hoveredNode, progress);
    }

    private void syncClientTotals(ResearchDialog dialog, TechNode rootNode) {
        if (dialog == null || dialog.itemDisplay == null) {
            return;
        }

        ResearchItemSnapshot snapshot = getSnapshot(rootNode);
        if (snapshot == null || snapshot.items().isEmpty()) {
            return;
        }

        String totalsSignature = buildTotalsSignature(snapshot);
        if (Objects.equals(lastTotalsSignature, totalsSignature) && lastRootNode == rootNode) {
            return;
        }

        ItemSeq items = toItemSeq(snapshot);
        dialog.itemDisplay.visible(() -> true);
        dialog.itemDisplay.rebuild(items);
        dialog.itemDisplay.invalidate();
        dialog.itemDisplay.layout();
        lastTotalsSignature = totalsSignature;
    }

    private void refreshInfoTableLayout(Table infoTable) {
        if (infoTable == null) {
            return;
        }

        infoTable.invalidateHierarchy();
        infoTable.pack();
        infoTable.act(0f);
    }

    private void syncRequestButton(Table infoTable, TechNode rootNode, TechNode hoveredNode) {
        Element existing = infoTable.find(REQUEST_BUTTON_NAME);
        if (existing != null) {
            existing.remove();
        }

        boolean visible = hoveredNode != null
            && hoveredNode.content != null
            && validator.canClientRequestResearch(hoveredNode.content.name);
        lastRequestVisible = visible;

        if (!visible) {
            return;
        }

        TextButton button = new TextButton(Core.bundle.get(REQUEST_BUNDLE_KEY), Styles.defaultt);
        button.name = REQUEST_BUTTON_NAME;
        button.clicked(() -> requestResearch(rootName(rootNode), hoveredNode.content.name));
        infoTable.row();
        infoTable.add(button).left().growX().height(44f).padTop(4f);
    }

    private boolean shouldRefreshInfo(TechNode rootNode, TechNode hoveredNode) {
        String nextSignature = buildInfoSignature(getSnapshot(rootNode), hoveredNode, getProgressSnapshot(hoveredNode));
        boolean requestVisible = hoveredNode != null
            && hoveredNode.content != null
            && validator.canClientRequestResearch(hoveredNode.content.name);

        return lastRootNode != rootNode
            || lastHoveredNode != hoveredNode
            || !Objects.equals(lastInfoSignature, nextSignature)
            || lastRequestVisible != requestVisible;
    }

    private ResearchItemSnapshot getSnapshot(TechNode rootNode) {
        if (rootNode == null || rootNode.content == null) {
            return null;
        }
        return clientResearchState.itemSnapshotsByRoot().get(rootNode.content.name);
    }

    private ResearchProgressSnapshot getProgressSnapshot(TechNode hoveredNode) {
        if (hoveredNode == null || hoveredNode.content == null) {
            return null;
        }
        return clientResearchState.progressByNode().get(hoveredNode.content.name);
    }

    private void addProgressRow(Table table, TechNode hoveredNode, ResearchProgressSnapshot progressSnapshot) {
        if (progressSnapshot == null) {
            return;
        }

        int required = 0;
        int finished = 0;
        for (ItemStack requirement : hoveredNode.requirements) {
            required += requirement.amount;
            finished += progressSnapshot.finishedAmountsByItem().get(requirement.item.name, 0);
        }

        if (finished <= 0 || required <= 0) {
            return;
        }

        float used = 0f;
        float sum = 0f;
        for (ItemStack requirement : hoveredNode.requirements) {
            int completed = progressSnapshot.finishedAmountsByItem().get(requirement.item.name, 0);
            sum += requirement.item.cost * requirement.amount;
            used += requirement.item.cost * completed;
        }

        int percent = Math.min((int) (used / sum * 100f), 99);
        Label label = table.add(Core.bundle.format(PROGRESS_BUNDLE_KEY, percent)).left().get();
        label.setColor(Color.lightGray);
        table.row();
    }

    private void addRequirementRows(
        Table table,
        TechNode hoveredNode,
        ResearchProgressSnapshot progressSnapshot,
        ResearchItemSnapshot itemSnapshot
    ) {
        if (itemSnapshot == null) {
            return;
        }

        for (ItemStack requirement : hoveredNode.requirements) {
            int completed = progressSnapshot == null
                ? 0
                : progressSnapshot.finishedAmountsByItem().get(requirement.item.name, 0);
            int remaining = requirement.amount - completed;
            if (remaining <= 0) {
                continue;
            }

            int available = itemSnapshot.items().get(requirement.item.name, 0);
            Color targetColor = available > 0 ? Color.lightGray : Color.scarlet;

            table.table(list -> {
                list.left();
                list.image(requirement.item.uiIcon).size(8 * 3).padRight(3);
                list.add(requirement.item.localizedName).color(Color.lightGray);
                Label label = list.add(" " + Core.bundle.format(AMOUNT_BUNDLE_KEY, Math.min(available, remaining), remaining)).left().get();
                label.setColor(targetColor);
            }).fillX().left();
            table.row();
        }
    }

    private String buildInfoSignature(
        ResearchItemSnapshot snapshot,
        TechNode hoveredNode,
        ResearchProgressSnapshot progressSnapshot
    ) {
        StringBuilder builder = new StringBuilder();

        if (hoveredNode != null && hoveredNode.content != null && progressSnapshot != null) {
            builder.append(hoveredNode.content.name).append(':');
            for (var entry : progressSnapshot.finishedAmountsByItem().entries()) {
                builder.append(entry.key).append('=').append(entry.value).append(';');
            }
        }

        if (snapshot != null && !snapshot.items().isEmpty()) {
            builder.append("root:").append(snapshot.rootName()).append(':');
            for (var entry : snapshot.items().entries()) {
                builder.append(entry.key).append('=').append(entry.value).append(';');
            }
        }

        return builder.toString();
    }

    private String buildTotalsSignature(ResearchItemSnapshot snapshot) {
        StringBuilder builder = new StringBuilder(snapshot.rootName()).append(':');
        for (var entry : snapshot.items().entries()) {
            builder.append(entry.key).append('=').append(entry.value).append(';');
        }
        return builder.toString();
    }

    private ItemSeq toItemSeq(ResearchItemSnapshot snapshot) {
        ItemSeq items = new ItemSeq();
        Vars.content.items().each(item -> {
            int amount = snapshot.items().get(item.name, 0);
            if (amount > 0) {
                items.add(item, amount);
            }
        });
        return items;
    }

    private String rootName(TechNode rootNode) {
        return rootNode == null || rootNode.content == null ? "" : rootNode.content.name;
    }
}
