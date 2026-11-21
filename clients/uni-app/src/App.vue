<script setup lang="ts">
/**
 * App shell — no business wizards.
 * 应用壳：不做业务向导。
 *
 * On launch: adopt tenantId from the query if present, then silently refresh
 * an existing end-user session. Public questionnaire fill does not require login.
 * 启动时若查询带租户则写入，并静默刷新已有终端会话。公开问卷填写不强制登录。
 */
import { onLaunch, onShow, onHide } from "@dcloudio/uni-app";
import { ensureSession } from "./api/auth";
import { applyTenantFromQuery } from "./api/tenant";

onLaunch((options) => {
  console.log("[subjex-clients] App Launch");
  applyTenantFromQuery((options && options.query) as Record<string, unknown> | undefined);
  void ensureSession();
});
onShow(() => {
  console.log("[subjex-clients] App Show");
});
onHide(() => {
  console.log("[subjex-clients] App Hide");
});
</script>

<style>
page {
  background-color: #f8f8f8;
  color: #1a1a1a;
  font-size: 14px;
  line-height: 1.5;
}
</style>
