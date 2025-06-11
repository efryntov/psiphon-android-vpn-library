import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ca.psiphon.library"
    compileSdk = 35

    defaultConfig {
        minSdk = 16
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // tun2socks NDK build configuration
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        externalNativeBuild {
            ndkBuild {
                cFlags("-DNDEBUG")
            }
        }
    }

    ndkVersion = "17.2.4988734"

    externalNativeBuild {
        ndkBuild {
            path("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    // Direct AAR file reference
    compileOnly(files("libs/ca.psiphon.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Custom task that repackages the AAR with modifications
tasks.register("buildLibraryAar") {
    val sourceAAR = file("libs/ca.psiphon.aar")
    val outputAAR = file("build/libs/ca.psiphon.vpn.library.aar")
    val tempDir = file("build-tmp/tunnel-core-library-unpacked")
    val buildTmp = file("build-tmp")

    // Depend on Kotlin compilation AND native build AND AIDL compilation
    dependsOn("compileReleaseKotlin")
    dependsOn("compileReleaseJavaWithJavac")
    dependsOn("compileReleaseAidl")
    dependsOn("externalNativeBuildRelease")

    inputs.file(sourceAAR)
    inputs.dir("src/main")
    outputs.file(outputAAR)

    doLast {
        println("Building modified AAR...")

        // 1. Clean and create temp directory
        delete(buildTmp)
        tempDir.mkdirs()

        // 2. Extract original AAR
        copy {
            from(zipTree(sourceAAR))
            into(tempDir)
        }

        // 3. Use manifest merger with package override
        val originalAarManifest = file("$tempDir/AndroidManifest.xml")
        val yourProjectManifest = file("src/main/AndroidManifest.xml")

        if (yourProjectManifest.exists() && originalAarManifest.exists()) {
            println("Using manifest merger with package override...")

            val sdkDir = android.sdkDirectory
            val libDir = file("$sdkDir/cmdline-tools/latest/lib")

            val classpath = fileTree(libDir) {
                include("**/*.jar")
            }.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }

            val tempMergedManifest = file("$buildDir/temp-merged-manifest.xml")

            try {
                exec {
                    commandLine(
                        "java", "-cp", classpath,
                        "com.android.manifmerger.Merger",
                        "--main", originalAarManifest.absolutePath,
                        "--overlays", yourProjectManifest.absolutePath,
                        "--out", tempMergedManifest.absolutePath,
                        "--property", "PACKAGE=ca.psiphon.library",
                        "--remove-tools-declarations",
                        "--log", "INFO"
                    )
                }

                copy {
                    from(tempMergedManifest)
                    into(tempDir)
                    rename { "AndroidManifest.xml" }
                }

                delete(tempMergedManifest)
                println("  ✓ Manifests merged with package override!")

            } catch (e: Exception) {
                println("  ⚠️ Merger failed: ${e.message ?: "Unknown error"}")
                throw e
            }
        }

        // 4. Copy project resources (strings, drawables, IDs, etc.)
        val projectResDir = file("src/main/res")
        if (projectResDir.exists()) {
            println("Copying project resources (strings, drawables, IDs, etc.)...")
            copy {
                from(projectResDir)
                into("$tempDir/res")
            }

            // List what resources were copied for verification
            fileTree("$tempDir/res").visit {
                if (file.isFile) {
                    val resType = relativePath.segments.getOrNull(0) ?: "unknown"
                    println("  ✓ Added resource [$resType]: ${relativePath.segments.drop(1).joinToString("/")}")
                }
            }
        } else {
            println("  No project resources directory found at src/main/res")
        }

        // 5. Get your compiled Kotlin AND AIDL classes and add them to classes.jar
        val compiledClasses = file("build/intermediates/javac/release/compileReleaseJavaWithJavac/classes")
        val kotlinClasses = file("build/tmp/kotlin-classes/release")

        if (kotlinClasses.exists()) {
            println("Adding Kotlin classes to AAR...")
            ant.withGroovyBuilder {
                "jar"("destfile" to "$tempDir/classes.jar", "update" to true) {
                    "fileset"("dir" to kotlinClasses)
                }
            }
        }

        if (compiledClasses.exists()) {
            println("Adding Java classes to AAR...")
            ant.withGroovyBuilder {
                "jar"("destfile" to "$tempDir/classes.jar", "update" to true) {
                    "fileset"("dir" to compiledClasses)
                }
            }
        }

        // Manually compile AIDL Java files if they exist
        val aidlJavaDir = file("build/generated/aidl_source_output_dir/release/out")
        val aidlClassesDir = file("build/compiled-aidl-classes")

        if (aidlJavaDir.exists()) {
            println("Manually compiling AIDL Java files...")

            delete(aidlClassesDir)
            aidlClassesDir.mkdirs()

            // Find all AIDL Java files
            val aidlJavaFiles = fileTree(aidlJavaDir) {
                include("**/*.java")
            }.files

            if (aidlJavaFiles.isNotEmpty()) {
                println("Found AIDL Java files:")
                aidlJavaFiles.forEach { println("  - ${it.relativeTo(aidlJavaDir)}") }

                try {
                    // Compile AIDL Java files
                    exec {
                        commandLine(
                            "javac",
                            "-d", aidlClassesDir.absolutePath,
                            "-bootclasspath", "${android.bootClasspath.joinToString(":")}",
                            "-classpath", "$tempDir/classes.jar",  // Include original AAR classes
                            "-source", "1.8",
                            "-target", "1.8",
                            *aidlJavaFiles.map { it.absolutePath }.toTypedArray()
                        )
                    }

                    // Add compiled AIDL classes to JAR
                    ant.withGroovyBuilder {
                        "jar"("destfile" to "$tempDir/classes.jar", "update" to true) {
                            "fileset"("dir" to aidlClassesDir)
                        }
                    }

                    // Verify what AIDL classes were added
                    fileTree(aidlClassesDir).visit {
                        if (file.name.endsWith(".class")) {
                            println("  ✓ Compiled AIDL class: $relativePath")
                        }
                    }

                    println("  ✓ AIDL classes compiled and added to JAR")

                } catch (e: Exception) {
                    println("  ⚠️ AIDL compilation failed: ${e.message ?: "Unknown error"}")
                    println("  AIDL services may not work in final AAR")
                }

                // Cleanup
                delete(aidlClassesDir)

            } else {
                println("  No AIDL Java files found to compile")
            }
        } else {
            println("  No AIDL output directory found")
        }

        // 6. Add native tun2socks libraries
        val nativeLibsDir = file("build/intermediates/ndkBuild/release/obj/local")
        if (nativeLibsDir.exists()) {
            println("Adding native tun2socks libraries...")
            copy {
                from(nativeLibsDir)
                into("$tempDir/jni")
                include("**/*.so")
            }

            // List what was added for verification
            fileTree("$tempDir/jni").visit {
                if (file.name.endsWith(".so")) {
                    println("  ✓ Added native lib: $relativePath")
                }
            }
        } else {
            println("⚠️  No native libraries found in $nativeLibsDir")
        }

        // 7. Update proguard.txt
        val proguardFile = file("$tempDir/proguard.txt")
        val projectProguardFile = file("proguard-rules.pro")
        if (projectProguardFile.exists()) {
            println("Merging project proguard rules...")
            proguardFile.appendText("\n# Rules from psiphon library project proguard-rules.pro\n")
            proguardFile.appendText(projectProguardFile.readText())
        }

        // 8. Create build info file with comprehensive git status
        println("Adding build metadata...")
        val buildInfoFile = file("$tempDir/build-info.txt")

        // Get git commit hash
        val gitCommitHash = try {
            val result = ByteArrayOutputStream()
            exec {
                commandLine("git", "rev-parse", "HEAD")
                standardOutput = result
                isIgnoreExitValue = true
            }
            result.toString().trim()
        } catch (e: Exception) {
            "unknown"
        }

        // Get short commit hash
        val gitShortHash = try {
            val result = ByteArrayOutputStream()
            exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                standardOutput = result
                isIgnoreExitValue = true
            }
            result.toString().trim()
        } catch (e: Exception) {
            "unknown"
        }

        // Check if working directory is dirty
        val gitStatus = try {
            val result = ByteArrayOutputStream()
            exec {
                commandLine("git", "status", "--porcelain")
                standardOutput = result
                isIgnoreExitValue = true
            }
            result.toString().trim()
        } catch (e: Exception) {
            ""
        }

        val isDirty = gitStatus.isNotEmpty()
        val dirtyFiles = if (isDirty) {
            gitStatus.lines().take(10) // Limit to first 10 files
        } else {
            emptyList()
        }

        // Get branch name
        val gitBranch = try {
            val result = ByteArrayOutputStream()
            exec {
                commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
                standardOutput = result
                isIgnoreExitValue = true
            }
            result.toString().trim()
        } catch (e: Exception) {
            "unknown"
        }


        val buildInfo = """
AAR Build Information
=====================
Git Commit: $gitCommitHash
Short Hash: $gitShortHash
Branch: $gitBranch
Working Dir: ${if (isDirty) "DIRTY ⚠️" else "CLEAN ✓"}

${if (isDirty) """
Uncommitted Changes:
${dirtyFiles.joinToString("\n") { "  $it" }}
${if (gitStatus.lines().size > 10) "  ... and ${gitStatus.lines().size - 10} more files" else ""}
""" else ""}
""".trimIndent()

        buildInfoFile.writeText(buildInfo)

        val statusEmoji = if (isDirty) "⚠️" else "✓"
        println("  $statusEmoji Added build-info.txt - Git: ${gitShortHash}${if (isDirty) " (DIRTY)" else " (clean)"}")

        // 9. Repack AAR
        delete(outputAAR)  // Remove old AAR
        ant.withGroovyBuilder {
            "zip"("destfile" to outputAAR) {
                "fileset"("dir" to tempDir)
            }
        }

        // 10. Cleanup
        delete(buildTmp)

        println("Modified AAR created: $outputAAR")

        // 11. Quick verification
        println("\n=== AAR Contents Verification ===")
        exec {
            commandLine("unzip", "-l", outputAAR.absolutePath)
            isIgnoreExitValue = true
        }

        // 12. Show where to find the built AAR
        println("\n=== Built AAR Location ===")
        println("📁 Directory: file//${outputAAR.parentFile.absolutePath}/")
        println("📦 AAR File: ${outputAAR.name}")
        println("💾 Size: ${outputAAR.length() / 1024}KB")
        if (outputAAR.exists()) {
            println("✅ Ready to use!")
        } else {
            println("❌ AAR not found!")
        }

    }
}