package com.shinoyuki.slipstream;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * requireClient 判定逻辑的单测。断言具体缺失清单（项名 + 顺序 + "只要求服务端开了的"），
 * 删掉 missingCapabilities 任一分支这些断言都会挂。
 */
class RequireClientPolicyTest {

    @Test
    void serverEnablesNothing_neverRequires() {
        // 服务端一个协商优化都没开 -> 不论客户端装没装, 都放行。
        assertTrue(RequireClientPolicy.missingCapabilities(false, false, false, false, false, false).isEmpty());
        assertTrue(RequireClientPolicy.missingCapabilities(false, true, false, true, false, true).isEmpty());
    }

    @Test
    void serverEnabled_clientMissing_listsThatCapability() {
        List<String> m = RequireClientPolicy.missingCapabilities(true, false, false, false, false, false);
        assertEquals(List.of("zstd 压缩"), m);
    }

    @Test
    void serverEnabled_clientHasIt_passes() {
        assertTrue(RequireClientPolicy.missingCapabilities(true, true, false, false, true, true).isEmpty());
    }

    @Test
    void allThreeServerOn_clientHasNone_listsAllInOrder() {
        List<String> m = RequireClientPolicy.missingCapabilities(true, false, true, false, true, false);
        assertEquals(List.of("zstd 压缩", "小包聚合", "实体字段 delta"), m);
    }

    @Test
    void serverDisabledCapability_isNotRequired_evenIfClientLacksIt() {
        // 服务端没开聚合 -> 即便客户端也没开, 聚合不进要求; 只缺服务端开了的 zstd。
        List<String> m = RequireClientPolicy.missingCapabilities(true, false, false, false, false, false);
        assertEquals(List.of("zstd 压缩"), m);
    }

    @Test
    void partialMatch_onlyListsTheMissingOne() {
        // 服务端开 zstd + L2, 客户端有 zstd 缺 L2 -> 只缺实体字段 delta。
        List<String> m = RequireClientPolicy.missingCapabilities(true, true, false, false, true, false);
        assertEquals(List.of("实体字段 delta"), m);
    }
}
