<script setup lang="ts">
/**
 * App shell — no business wizards.
 * 应用壳：不做业务向导。
 */
import { onLaunch, onShow, onHide } from "@dcloudio/uni-app";

onLaunch(() => {
  console.log("[subjex-clients] App Launch");
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
