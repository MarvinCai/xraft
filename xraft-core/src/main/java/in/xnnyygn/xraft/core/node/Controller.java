package in.xnnyygn.xraft.core.node;

import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import in.xnnyygn.xraft.core.log.Log;
import in.xnnyygn.xraft.core.log.entry.Entry;
import in.xnnyygn.xraft.core.log.entry.EntryListenerAdapter;
import in.xnnyygn.xraft.core.log.entry.GroupConfigEntry;
import in.xnnyygn.xraft.core.log.replication.GeneralReplicationStateTracker;
import in.xnnyygn.xraft.core.log.replication.SelfReplicationState;
import in.xnnyygn.xraft.core.log.entry.EntryApplierAdapter;
import in.xnnyygn.xraft.core.noderole.*;
import in.xnnyygn.xraft.core.rpc.Connector;
import in.xnnyygn.xraft.core.rpc.message.*;
import in.xnnyygn.xraft.core.schedule.ElectionTimeout;
import in.xnnyygn.xraft.core.schedule.LogReplicationTask;
import in.xnnyygn.xraft.core.service.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Controller implements NodeRoleContext {

    private static final Logger logger = LoggerFactory.getLogger(Controller.class);
    private final NodeContext nodeContext;
    private AbstractNodeRole nodeRole;
    private final List<NodeRoleListener> nodeRoleListeners = new ArrayList<>();
    private final ListeningExecutorService executorService;

    Controller(NodeContext nodeContext) {
        this.nodeContext = nodeContext;
        this.nodeContext.register(this);
        this.executorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor(
                r -> new Thread(r, "controller-" + nodeContext.getSelfNodeId()))
        );
    }

    void start() {
        this.nodeContext.initialize();
        this.changeToNodeRole(new FollowerNodeRole(this.nodeContext.getNodeStore(), this.scheduleElectionTimeout()));
    }

    RoleStateSnapshot getRoleState() {
        return this.nodeRole.takeSnapshot();
    }

    void registerStateMachine(StateMachine stateMachine) {
        this.nodeContext.getLog().setEntryApplier(new EntryApplierAdapter(stateMachine));
        this.nodeContext.getLog().setSnapshotGenerator(stateMachine);
        this.nodeContext.getLog().setSnapshotApplier(stateMachine);
    }

    void appendLog(byte[] command) {
        ensureLeader();
        this.runWithMonitor(() -> {
            this.nodeContext.getLog().appendEntry(this.nodeRole.getTerm(), command);
            ((LeaderNodeRole) this.nodeRole).replicateLog(this);
        });
    }

//    void changeMembership(Set<NodeConfig> newNodeConfigs) {
//        if (this.nodeRole.getRole() != RoleName.LEADER) {
//            throw new IllegalStateException("only leader can append log");
//        }
//
//        this.runWithMonitor(() -> {
//            Set<NodeConfig> oldNodeConfigs = this.nodeContext.getNodeGroup().toNodeConfigs();
//            Set<NodeConfig> mergedNodeConfigs = NodeConfig.merge(oldNodeConfigs, newNodeConfigs);
//            this.nodeRole.applyNodeConfigs(this, mergedNodeConfigs);
//
//            // wait for node committed
//            if (!NodeConfig.isSame(mergedNodeConfigs, newNodeConfigs)) {
//                this.nodeRole.applyNodeConfigs(this, newNodeConfigs);
//            }
//        });
//    }

    private void ensureLeader() {
        if (this.nodeRole.getRole() != RoleName.LEADER) {
            throw new IllegalStateException("only leader can perform such operation");
        }
    }

    void addServer(NodeConfig newNodeConfig) {
        ensureLeader();
        // TODO wrap in executor
        this.runWithMonitor(() -> {
            // TODO catch up new server

            GroupConfigEntry lastEntry = nodeContext.getLog().getLastGroupConfigEntry();
            // check if last group config entry committed
            if (lastEntry != null && nodeContext.getLog().getCommitIndex() < lastEntry.getIndex()) {
                // if not committed, wait for last config to be committed
                logger.info("wait for last group config entry {} to be committed", lastEntry);
                // since controller is single thread, no race condition to add listener
                lastEntry.addListener(new EntryListenerAdapter() {
                    @Override
                    public void entryCommitted(Entry entry) {
                        Controller.this.addServer(newNodeConfig);
                    }
                });
                return;
            }

            nodeContext.getNodeGroup().addNode(newNodeConfig);
            // now connector can use new node config
            nodeContext.getLog().appendEntry(nodeRole.getTerm(), nodeContext.getNodeGroup().toNodeConfigs());
            ((LeaderNodeRole) nodeRole).replicateLog(this);
        });
    }

    void removeServer(NodeConfig nodeConfig) {

    }

    void addNodeRoleListener(NodeRoleListener listener) {
        this.nodeRoleListeners.add(listener);
    }

    void stop() throws InterruptedException {
        this.nodeRole.cancelTimeoutOrTask();

        this.executorService.shutdown();
        try {
            this.executorService.awaitTermination(1L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        this.nodeContext.release();
    }

    @Subscribe
    public void onReceive(RequestVoteRpcMessage rpcMessage) {
        this.runWithMonitor(() -> {
            this.nodeRole.onReceiveRequestVoteRpc(this, rpcMessage);
        });
    }

    @Subscribe
    public void onReceive(RequestVoteResult result) {
        this.runWithMonitor(() -> {
            this.nodeRole.onReceiveRequestVoteResult(this, result);
        });
    }

    @Subscribe
    public void onReceive(AppendEntriesRpcMessage rpcMessage) {
        this.runWithMonitor(() -> {
            this.nodeRole.onReceiveAppendEntriesRpc(this, rpcMessage);
        });
    }

    @Subscribe
    public void onReceive(AppendEntriesResultMessage resultMessage) {
        this.runWithMonitor(() -> {
            this.nodeRole.onReceiveAppendEntriesResult(this, resultMessage.getResult(), resultMessage.getSourceNodeId(), resultMessage.getRpc());
        });
    }

    @Subscribe
    public void onReceive(InstallSnapshotRpcMessage rpcMessage) {
        this.runWithMonitor(() -> {
            this.nodeRole.onReceiveInstallSnapshotRpc(this, rpcMessage);
        });
    }

    @Subscribe
    public void onReceive(InstallSnapshotResultMessage resultMessage) {
        this.runWithMonitor(() -> {
            // TODO add channel to result message
            this.nodeRole.onReceiveInstallSnapshotResult(this, resultMessage.getResult(), resultMessage.getSourceNodeId(), resultMessage.getRpc());
        });
    }

    @Override
    public NodeId getSelfNodeId() {
        return this.nodeContext.getSelfNodeId();
    }

    @Override
    public int getNodeCountForVoting() {
        return this.nodeContext.getNodeGroup().getCount();
    }

    @Override
    public GeneralReplicationStateTracker createReplicationStateTracker() {
        Set<NodeId> nodeIds = new HashSet<>(this.nodeContext.getNodeGroup().getIds());
        nodeIds.remove(this.nodeContext.getSelfNodeId());
        return new GeneralReplicationStateTracker(
                nodeIds,
                this.nodeContext.getLog().getNextLogIndex(),
                new SelfReplicationState(this.nodeContext.getSelfNodeId(), this.nodeContext.getLog())
        );
    }

    @Override
    public void changeToNodeRole(AbstractNodeRole newNodeRole) {
        logger.debug("node {}, state changed {} -> {}", this.getSelfNodeId(), this.nodeRole, newNodeRole);

        // notify listener if not stable
        if (!isStableBetween(this.nodeRole, newNodeRole)) {
            RoleStateSnapshot snapshot = newNodeRole.takeSnapshot();
            this.nodeRoleListeners.forEach((l) -> l.nodeRoleChanged(snapshot));
        }

        this.nodeRole = newNodeRole;
    }

    private boolean isStableBetween(AbstractNodeRole before, AbstractNodeRole after) {
        return before != null &&
                before.getRole() == RoleName.FOLLOWER && after.getRole() == RoleName.FOLLOWER &&
                FollowerNodeRole.isStableBetween((FollowerNodeRole) before, (FollowerNodeRole) after);
    }

    @Override
    public LogReplicationTask scheduleLogReplicationTask() {
        ensureLeader();
        return this.nodeContext.getScheduler().scheduleLogReplicationTask(
                () -> this.runWithMonitor(() -> ((LeaderNodeRole) this.nodeRole).replicateLog(this))
        );
    }

    @Override
    public Log getLog() {
        return this.nodeContext.getLog();
    }

    @Override
    public Connector getConnector() {
        return this.nodeContext.getConnector();
    }

    @Override
    public ElectionTimeout scheduleElectionTimeout() {
        return this.nodeContext.getScheduler().scheduleElectionTimeout(
                () -> this.runWithMonitor(() -> this.nodeRole.electionTimeout(this))
        );
    }

    private void runWithMonitor(Runnable r) {
        this.nodeContext.runWithMonitor(this.executorService, r);
    }

}
