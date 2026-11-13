# S25 Compute

Android starter for a private compute service. No VpnService or root required.

Open in Android Studio with SDK 35 and JDK 17 or later, then build/install the app. Start the service in the app. It listens only on 127.0.0.1:8787. In TailSocks select Proxy mode and configure tailnet-only Serve to forward to http://127.0.0.1:8787. Leave Funnel disabled.

Copy the private token displayed in the app. Requests require `Authorization: Bearer TOKEN`.

```sh
curl -H "Authorization: Bearer $TOKEN" https://PHONE_NAME/capabilities
curl -X POST -H "Authorization: Bearer $TOKEN" https://PHONE_NAME/jobs/sha256
curl -H "Authorization: Bearer $TOKEN" https://PHONE_NAME/jobs/JOB_ID
```

The SHA-256 task hashes 256 MiB of generated data. One job runs at a time; results are in memory and expire after 32 jobs or service shutdown. Stop the service in the app to interrupt work.

GPU jobs execute an OpenGL ES 3.1 compute shader and verify readback. Submit integers with `POST /jobs/gpu-vector?values=1,2,3`; the result is `3*x+7` modulo 2^32 for each value, reported as unsigned integers. Up to 512 signed integers can be supplied. The default job generates 1024 inputs. This API is a first compute kernel, not a general remote GPU driver.

Cancel using `DELETE /jobs/JOB_ID`. CPU cancellation is cooperative; a GPU dispatch already submitted completes before cancellation is acknowledged. One worker runs at a time. The app includes local submission and result controls. Copy the token with the Copy API token button.

Build: `JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug`. Install `app/build/outputs/apk/debug/app-debug.apk`. See VALIDATION.md for phone checks and tools/compute-client.py for desktop use.

Device limits: no boot start; Android can suspend or kill the process. This first API has a fixed CPU hashing task and one GPU vector kernel. Physical Adreno execution and TailSocks routing remain for phone validation. The app uses a visible foreground service notification, but does not request Android VPN permission.

CPU text input: `POST /jobs/sha256?text=hello` (URL encoded). The desktop client supports `--text hello` and GPU `--values 1,2,3`. Requests including headers are limited to 8 KiB. Jobs hold a bounded wake lock during execution; idle availability still depends on Android power management. GPU cancellation is cooperative at dispatch boundaries.
