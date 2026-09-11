package com.lemonlightmc.minecicd.api;

import java.util.List;

import com.lemonlightmc.minecicd.git.CommitActions.CommitAction;
import com.lemonlightmc.minecicd.http.ProgressStream;

public interface ICicdService2 {

    boolean tryAcquireInFlight(String requestId);

    void releaseInFlight(String requestId);

    boolean isBusy();

    void acceptRequest(String requestId, List<CommitAction> actions, String branch);

    ProgressStream progressStream(String requestId);

    void removeRequest(String requestId);

    void onServerStarted();

    void shutdown();

}