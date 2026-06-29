package com.shinoyuki.slipstream;

import java.util.ArrayList;
import java.util.List;

/**
 * requireClient 强制双端策略的判定纯函数。给定服务端各协商优化是否启用、以及对端是否也支持，算出
 * 客户端缺失的、服务端要求的能力清单。抽成无副作用纯函数以便单测；登录 mixin 只负责取值与踢人。
 *
 * <p>只对服务端自己启用的能力提要求：服务端没开的优化，即便客户端没开也不强制（不在要求范围内）。
 * 每个 remoteSupports 信号本身已是"本端开了该能力且对端也开了"的语义，故 remote=false 即代表客户端
 * 未安装 Slipstream 或未启用该项。
 */
public final class RequireClientPolicy {

    /**
     * @return 服务端已启用、但客户端未启用的协商能力的可读名清单（用于踢出提示）；空列表表示放行。
     */
    public static List<String> missingCapabilities(
            boolean zstdServer, boolean zstdRemote,
            boolean aggregateServer, boolean aggregateRemote,
            boolean l2Server, boolean l2Remote) {
        List<String> missing = new ArrayList<>(3);
        if (zstdServer && !zstdRemote) {
            missing.add("zstd 压缩");
        }
        if (aggregateServer && !aggregateRemote) {
            missing.add("小包聚合");
        }
        if (l2Server && !l2Remote) {
            missing.add("实体字段 delta");
        }
        return missing;
    }

    private RequireClientPolicy() {
    }
}
