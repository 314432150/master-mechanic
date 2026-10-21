# 立牌流程标定产物（临时需求 temp/badge-share-1021）

> 2026-10-21 用户真机框选完成（批 1）。产物：`files/calibration/calibration.txt`（568 KB，2026-10-21 23:21）。
> 本文件记录**结构取证**（从设备 `run-as` 直读），不含模板像素。活动结束随分支移除。

## 五屏结构（state= / anchor= 行）

```
state=ACTIVITY_CENTER|badge_claim_entry
state=BADGE_CLAIM|badge_claim_e1
state=BADGE_MY|badge_my_e1
state=BADGE_SHARE|badge_share_e1

anchor=ACTIVITY_CENTER|badge_claim_entry,badge_activity_exit,badge_share_entry,badge_empty_slot
anchor=BADGE_CLAIM|badge_claim,badge_claim_close,badge_continue
anchor=BADGE_MY|badge_share,badge_my_close
anchor=BADGE_SHARE|badge_share_text_area,badge_share_close,badge_share_score_area,badge_share_name_area,badge_switch
anchor=HALL|hall_settings,hall_farm,hall_activity   （活动入口是本次唯一的"旧屏新锚点"）
```

## 信号窗口（归一化比例，0..1）与用途

| 信号 | 角色 | 窗口 (l,t,r,b) | 用途 | 模板数 |
| --- | --- | --- | --- | --- |
| `badge_claim_entry` | marker + anchor | 0.345,0.824,0.455,0.941 / 0.340,0.813,0.460,0.952 | 活动中心标志 / 获取立牌入口 | 1 + 1 |
| `badge_claim_e1` | marker | 0.421,0.128,0.574,0.281 | 获取立牌弹框标志（标题位） | 1 |
| `badge_my_e1` | marker | 0.425,0.139,0.573,0.280 | 我的立牌弹框标志（标题位） | 1 |
| `badge_share_e1` | marker | 0.396,0.140,0.485,0.274 | 复制分享弹框标志（标题位） | 1 |
| `badge_activity_exit` | anchor | 0.049,0.000,0.134,0.104 | 返回（左上 «） | 1 |
| `badge_share_entry` | anchor | 0.206,0.865,0.294,0.982 | 分享立牌（左下） | 1 |
| `badge_empty_slot` | anchor | 0.402,0.315,0.477,0.488 | 空立牌槽（只判存在） | 1 |
| `badge_claim` | anchor | 0.638,0.269,0.709,0.397 | 领取（登录游戏行） | 1 |
| `badge_claim_close` | anchor | 0.836,0.056,0.902,0.194 | 关闭（X） | 1 |
| `badge_continue` | anchor | 0.441,0.860,0.558,0.990 | 点击继续 | 1 |
| `badge_share` | anchor | 0.400,0.628,0.470,0.752 | 分享（我的立牌卡上，**多实例**） | 1 |
| `badge_my_close` | anchor | 0.813,0.049,0.910,0.242 | 关闭（X） | 1 |
| `badge_switch` | anchor | 0.331,0.700,0.402,0.826 | 切换 | 1 |
| `badge_share_score_area` | anchor（只圈区域） | 0.343,0.633,0.373,0.668 | 分数识别区域 | 1 |
| `badge_share_name_area` | anchor（只圈区域） | 0.259,0.680,0.444,0.722 | 立牌名称识别区域 | 1 |
| `badge_share_text_area` | anchor（只圈区域） | 0.583,0.526,0.651,0.569 | 分享码文本区 | 1 |
| `badge_share_close` | anchor | 0.835,0.057,0.901,0.194 | 关闭（X） | 1 |

**结论**：契约要求的 17 个立牌信号 + HALL 的 `hall_activity` 全部有模板、用途与窗口；无缺失。

## 风险注记（实现期须处理）

1. **ACTIVITY_CENTER 的标志是「获取立牌」按钮本身**（`badge_claim_entry` 同时当标志与锚点）。
   三态对照帧已证：未获取 / 已获取未提交时它都在；**已提交后它消失**（被「今日立牌已提交，请明日再来」取代）。
   ⇒ 子账号流程从不提交，不影响；但若某账号当天被手动提交过，活动中心将识别不出来（流程按 FR-T5 停下报原因）。
2. **`badge_share`（我的立牌·分享）是同模板多实例**（两张卡两个按钮）：运行时要按卡/位置消歧，不能"挑最像的"。
3. **`badge_share` 的卡序不固定**：分享第二个按钮的位置两次框选间可能不同（多命中时按左右序取，见流程实现）。
4. **`badge_empty_slot` 窗口偏大**（y 0.315→0.488，约占屏高 17%）：判存在用，模板才是主证据；
   若误命中率高（蛋糕装饰相似），按"未命中 ⇒ 已获取、命中 ⇒ 未获取"口径先跑真机再调。
