# 模型推理等级字段缺失修复计划

## 问题定位

`ReasoningLevel` 实际包含 7 个等级：`关闭、自动、低、中等、高、超高、最大`，对应字符串资源也已存在。缺失发生在 `ReasoningPicker.kt` 的展示层：刻度标签被白名单限制，只显示了 `关闭、自动、低、最大`，导致 `中等、高、超高` 位置为空。

## 修复方案

1. 修改 `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ReasoningPicker.kt`，移除仅显示部分等级的标签过滤逻辑。
2. 按 `ReasoningLevel.entries` 顺序完整渲染 7 个档位；窄屏使用可换行布局或紧凑标签，避免文字重叠和截断。
3. 滑块拖动时同步更新当前图标、等级文字和选中状态；松手或点击档位后再写入实际配置。
4. 保持现有枚举值、序列化字段和请求参数不变，避免影响各模型供应商的推理参数映射。

## 验证要点

- 中文界面完整显示：`关闭、自动、低、中等、高、超高、最大`。
- 拖动和点击均能准确选择 7 个等级，选中状态与顶部文字一致。
- 在常见窄屏、横屏及英文界面下无重叠、裁切或底部溢出。
- 执行 Compose 预览检查，并运行 `./gradlew :app:compileDebugKotlin -x :app:processDebugGoogleServices`。
