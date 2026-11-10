# 第三方组件声明

## libadb.so（AOSP adb 34.0.0）

- 位置：`app/src/main/jniLibs/arm64-v8a/libadb.so`、`app/src/main/jniLibs/x86_64/libadb.so`
- 来源：AOSP `platform/packages/modules/adb`，二进制取自 https://github.com/tytydraco/LADB 的 `jniLibs`
- 许可证：Apache License 2.0（全文见同目录 `LICENSE-libadb.txt`）

改名的原因：Android 会把 `jniLibs` 下的文件解压到 `nativeLibraryDir` 并赋予可执行权限，
而 `assets` 下的文件不能直接执行。运行时以子进程方式调用它作为 adb server / client。

## Kadb

- 坐标：`com.flyfishxu:kadb`（Apache License 2.0）
- 用途：纯 Kotlin 实现的 ADB 客户端，直接与 adbd 通信，无需 adb 二进制
