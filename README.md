## media-codec

本库完全基于 Android MediaCodec 封装的高性能编解码器实现，内部没有任何第三方库和的 C/C++ 代码。      

[![](https://jitpack.io/v/Ysj001/media-codec.svg)](https://jitpack.io/#Ysj001/media-codec)



### 使用

1. 项目已经发到 `jitpack.io` 仓库，在项目根 `build.gradle.kts` 中配置如下

   ```kotlin
   // Top-level build file
   
   subprojects {
       repositories {
           maven { setUrl("https://jitpack.io") }
       }
   }
   ```

2. 在 `app` 模块的 `build.gradle.kts` 中的配置如下

   ```kotlin
   dependencies {
   	implementation("com.github.Ysj001:media-codec:<lastest-version>")
   }
   ```

