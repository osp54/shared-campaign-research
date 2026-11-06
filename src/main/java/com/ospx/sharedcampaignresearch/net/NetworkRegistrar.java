package com.ospx.sharedcampaignresearch.net;

import arc.Core;
import arc.struct.ObjectIntMap;
import arc.struct.OrderedMap;
import arc.util.Nullable;
import com.ospx.sharedcampaignresearch.SharedCampaignResearchMod;
import com.ospx.sharedcampaignresearch.domain.ResearchApplyService;
import com.ospx.sharedcampaignresearch.domain.ResearchApplyService.ApplyResearchResult;
import com.ospx.sharedcampaignresearch.domain.ResearchRequestValidator;
import com.ospx.sharedcampaignresearch.net.packet.ResearchRequestPacket;
import com.ospx.sharedcampaignresearch.net.packet.ResearchResponsePacket;
import com.ospx.sharedcampaignresearch.net.packet.ResearchSyncPacket;
import com.ospx.sharedcampaignresearch.net.packet.ResearchSyncRequestPacket;
import com.ospx.sharedcampaignresearch.sync.ResearchSyncApplier;
import com.ospx.sharedcampaignresearch.sync.ResearchProgressSnapshot;
import com.ospx.sharedcampaignresearch.sync.ResearchSyncService;
import com.ospx.sharedcampaignresearch.sync.ResearchSyncSnapshot;
import com.ospx.sharedcampaignresearch.util.StringsCompat;
import java.util.Objects;
import mindustry.Vars;
import mindustry.content.TechTree.TechNode;
import mindustry.net.Net;
import mindustry.net.NetConnection;

public final class NetworkRegistrar {

    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_PROGRESSED = "progressed";
    private static final String STATUS_UNAVAILABLE = "unavailable";
    private static final String STATUS_REJECTED = "rejected";
    private static final String INVALID_REQUEST_BUNDLE_KEY = "sharedcampaignresearch.net.invalid-request";
    private static final String HOST_ONLY_BUNDLE_KEY = "sharedcampaignresearch.net.host-only";
    private static final String NODE_NOT_FOUND_BUNDLE_KEY = "sharedcampaignresearch.net.node-not-found";
    private static final String ALREADY_UNLOCKED_BUNDLE_KEY = "sharedcampaignresearch.net.already-unlocked";
    private static final String REQUIREMENTS_MISSING_BUNDLE_KEY = "sharedcampaignresearch.net.requirements-missing";
    private static final String COMPLETED_BUNDLE_KEY = "sharedcampaignresearch.net.completed";
    private static final String PROGRESSED_BUNDLE_KEY = "sharedcampaignresearch.net.progressed";
    private static final String SYNC_UNAVAILABLE_BUNDLE_KEY = "sharedcampaignresearch.net.sync-unavailable";
    private static final String REQUEST_REJECTED_BUNDLE_KEY = "sharedcampaignresearch.net.request-rejected";

    private final ResearchApplyService applyService;
    private final ResearchRequestValidator validator;
    private final ResearchSyncApplier syncApplier;
    private final ResearchSyncService syncService;
    private boolean registered;

    public NetworkRegistrar(
        ResearchApplyService applyService,
        ResearchRequestValidator validator,
        ResearchSyncApplier syncApplier,
        ResearchSyncService syncService
    ) {
        this.applyService = Objects.requireNonNull(applyService, "applyService");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.syncApplier = Objects.requireNonNull(syncApplier, "syncApplier");
        this.syncService = Objects.requireNonNull(syncService, "syncService");
    }

    public void register() {
        if (registered) {
            return;
        }

        Net.registerPacket(ResearchRequestPacket::new);
        Net.registerPacket(ResearchResponsePacket::new);
        Net.registerPacket(ResearchSyncPacket::new);
        Net.registerPacket(ResearchSyncRequestPacket::new);
        Vars.net.handleServer(ResearchRequestPacket.class, this::handleResearchRequest);
        Vars.net.handleServer(ResearchSyncRequestPacket.class, this::handleSyncRequest);
        Vars.net.handleClient(ResearchResponsePacket.class, this::handleResearchResponse);
        Vars.net.handleClient(ResearchSyncPacket.class, this::handleResearchSync);

        registered = true;
        SharedCampaignResearchMod.log("network registrar initialized");
    }

    public boolean requestResearch(String nodeName) {
        if (StringsCompat.isBlank(nodeName)) {
            return false;
        }

        SharedCampaignResearchMod.log("request send: node=" + nodeName);
        Vars.net.send(new ResearchRequestPacket(nodeName), true);
        return true;
    }

    public void requestInitialSync(String rootName) {
        if (!Vars.net.client() || StringsCompat.isBlank(rootName)) {
            return;
        }

        SharedCampaignResearchMod.log("sync request send: root=" + rootName);
        Vars.net.send(new ResearchSyncRequestPacket(rootName), true);
    }

    private void handleResearchRequest(NetConnection connection, ResearchRequestPacket packet) {
        if (connection == null || packet == null) {
            return;
        }

        SharedCampaignResearchMod.log("request received: node=" + packet.nodeName + ", player=" + describeConnection(connection));

        if (StringsCompat.isBlank(packet.nodeName)) {
            connection.send(new ResearchResponsePacket("", STATUS_REJECTED, INVALID_REQUEST_BUNDLE_KEY), true);
            return;
        }

        ValidationResult validation = validateRequest(packet.nodeName);
        if (!validation.accepted()) {
            SharedCampaignResearchMod.log("request rejected: node=" + packet.nodeName + ", reason=" + validation.message());
            connection.send(new ResearchResponsePacket(packet.nodeName, "rejected", validation.message()), true);
            return;
        }

        ApplyResearchResult result = applyService.applyResearch(packet.nodeName);
        SharedCampaignResearchMod.log("request handled: node=" + packet.nodeName + ", result=" + result);
        connection.send(new ResearchResponsePacket(packet.nodeName, mapStatus(result), mapMessage(packet.nodeName, result)), true);

        if (result.isSuccess()) {
            SharedCampaignResearchMod.log("sync send after apply: node=" + packet.nodeName);
            connection.send(buildSyncPacket(packet.nodeName), true);
        }
    }

    private void handleResearchResponse(ResearchResponsePacket packet) {
        if (packet == null) {
            return;
        }

        String localizedMessage = localizeResponseMessage(packet);
        if (Vars.ui != null && !StringsCompat.isBlank(localizedMessage)) {
            Vars.ui.showInfoToast(localizedMessage, 3f);
        }

        SharedCampaignResearchMod.log(
            "research response received: node=" + packet.nodeName + ", status=" + packet.status + ", message=" + localizedMessage
        );
    }

    private void handleSyncRequest(NetConnection connection, ResearchSyncRequestPacket packet) {
        if (connection == null || packet == null || StringsCompat.isBlank(packet.rootName)) {
            return;
        }

        SharedCampaignResearchMod.log("sync request received: root=" + packet.rootName + ", player=" + describeConnection(connection));
        connection.send(buildSyncPacketForRoot(packet.rootName), true);
    }

    private void handleResearchSync(ResearchSyncPacket packet) {
        if (packet == null) {
            return;
        }

        syncApplier.applySnapshot(toSnapshot(packet));
        SharedCampaignResearchMod.log(
            "research sync received: root=" + packet.rootName
                + ", progressEntries=" + packet.progressNodeNames.size
                + ", itemEntries=" + packet.rootItemNames.size
        );
    }

    private ResearchSyncPacket buildSyncPacket(String nodeName) {
        ResearchSyncSnapshot snapshot = syncService.buildSnapshotForNode(nodeName);
        return toPacket(snapshot);
    }

    private ResearchSyncPacket buildSyncPacketForRoot(String rootName) {
        ResearchSyncSnapshot snapshot = syncService.buildSnapshotForRootName(rootName);
        return toPacket(snapshot);
    }

    private ResearchSyncPacket toPacket(ResearchSyncSnapshot snapshot) {
        var progressNodeNames = new arc.struct.Seq<String>();
        var progressItemNames = new arc.struct.Seq<String>();
        var progressFinishedAmounts = new arc.struct.Seq<Integer>();
        snapshot.progressByNode().each((nodeName, progressSnapshot) -> {
            for (var entry : progressSnapshot.finishedAmountsByItem().entries()) {
                progressNodeNames.add(nodeName);
                progressItemNames.add(entry.key);
                progressFinishedAmounts.add(entry.value);
            }
        });

        var itemNames = new arc.struct.Seq<String>();
        var itemAmounts = new arc.struct.Seq<Integer>();
        for (var entry : snapshot.rootItems().items().entries()) {
            itemNames.add(entry.key);
            itemAmounts.add(entry.value);
        }

        return new ResearchSyncPacket(
            snapshot.rootName(),
            progressNodeNames,
            progressItemNames,
            progressFinishedAmounts,
            itemNames,
            itemAmounts
        );
    }

    private ResearchSyncSnapshot toSnapshot(ResearchSyncPacket packet) {
        var progress = new OrderedMap<String, ResearchProgressSnapshot>();
        var progressItems = new OrderedMap<String, ObjectIntMap<String>>();
        for (var entry : packet.progressEntries()) {
            progressItems.get(entry.nodeName(), ObjectIntMap::new)
                .put(entry.itemName(), entry.finishedAmount());
        }
        progressItems.each((nodeName, finishedAmountsByItem) -> progress.put(nodeName, new ResearchProgressSnapshot(nodeName, finishedAmountsByItem)));

        var items = new ObjectIntMap<String>();
        for (var entry : packet.rootItemEntries()) {
            items.put(entry.key, entry.value);
        }

        return new ResearchSyncSnapshot(
            packet.rootName,
            progress,
            new com.ospx.sharedcampaignresearch.sync.ResearchItemSnapshot(packet.rootName, items)
        );
    }

    private ValidationResult validateRequest(String nodeName) {
        if (!applyService.shouldApplyResearch(nodeName)) {
            TechNode node = validator.findRequestedNode(nodeName);
            if (!Vars.net.server() || !Vars.state.isCampaign() || Vars.state.rules == null || Vars.state.rules.sector == null) {
                return ValidationResult.failure(HOST_ONLY_BUNDLE_KEY);
            }

            if (node == null) {
                return ValidationResult.failure(NODE_NOT_FOUND_BUNDLE_KEY);
            }

            if (node.content.unlockedHost()) {
                return ValidationResult.failure(ALREADY_UNLOCKED_BUNDLE_KEY);
            }

            if (!validator.canApplyHostSide(nodeName)) {
                return ValidationResult.failure(REQUIREMENTS_MISSING_BUNDLE_KEY);
            }
        }

        return ValidationResult.success();
    }

    private String mapStatus(ApplyResearchResult result) {
        return switch (result) {
            case COMPLETED -> STATUS_COMPLETED;
            case PROGRESSED -> STATUS_PROGRESSED;
            case UNAVAILABLE -> STATUS_UNAVAILABLE;
            case REJECTED -> STATUS_REJECTED;
        };
    }

    private String mapMessage(String nodeName, ApplyResearchResult result) {
        return switch (result) {
            case COMPLETED, PROGRESSED -> "";
            case UNAVAILABLE -> SYNC_UNAVAILABLE_BUNDLE_KEY;
            case REJECTED -> REQUEST_REJECTED_BUNDLE_KEY;
        };
    }

    private String localizeResponseMessage(ResearchResponsePacket packet) {
        if (packet == null) {
            return "";
        }

        return switch (packet.status) {
            case STATUS_COMPLETED -> Core.bundle.format(COMPLETED_BUNDLE_KEY, displayNodeName(packet.nodeName));
            case STATUS_PROGRESSED -> Core.bundle.format(PROGRESSED_BUNDLE_KEY, displayNodeName(packet.nodeName));
            case STATUS_UNAVAILABLE -> Core.bundle.get(SYNC_UNAVAILABLE_BUNDLE_KEY);
            case STATUS_REJECTED -> StringsCompat.isBlank(packet.message)
                ? Core.bundle.get(REQUEST_REJECTED_BUNDLE_KEY)
                : Core.bundle.get(packet.message, packet.message);
            default -> StringsCompat.isBlank(packet.message) ? packet.status : packet.message;
        };
    }

    private String displayNodeName(String nodeName) {
        TechNode node = validator.findRequestedNode(nodeName);
        if (node == null || node.content == null || StringsCompat.isBlank(node.content.localizedName)) {
            return nodeName;
        }
        return node.content.localizedName;
    }

    private String describeConnection(NetConnection connection) {
        if (connection.player == null) {
            return "<no-player>";
        }
        return connection.player.name == null ? "<unnamed>" : connection.player.name;
    }

    private static final class ValidationResult {

        private final boolean accepted;
        private final @Nullable String messageKey;

        private ValidationResult(boolean accepted, @Nullable String messageKey) {
            this.accepted = accepted;
            this.messageKey = messageKey;
        }

        private boolean accepted() {
            return accepted;
        }

        private @Nullable String message() {
            return messageKey;
        }

        private static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        private static ValidationResult failure(String message) {
            return new ValidationResult(false, message);
        }
    }
}
