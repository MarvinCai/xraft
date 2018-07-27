package in.xnnyygn.xraft.core.log.entry;

import java.util.List;

public interface Entry {

    int KIND_NO_OP = 0;
    int KIND_GENERAL = 1;
    int KIND_GROUP_CONFIG = 2;

    int getKind();

    int getIndex();

    int getTerm();

    byte[] getCommandBytes();

    List<EntryListener> getListeners();

    void removeAllListeners();

}
