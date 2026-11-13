# S25 Ultra device validation

Install the APK and open S25 Compute. Start service, check capabilities, then run CPU and GPU jobs. GPU completion must say verified=true and identify the Adreno renderer. A declared Vulkan feature alone is not execution evidence.

In TailSocks choose Proxy mode and configure Serve with target http://127.0.0.1:8787. Choose tailnet-only access; leave Funnel disabled. Copy the private API token using the app button.

On your computer:

```sh
export S25_COMPUTE_TOKEN='paste token'
python3 tools/compute-client.py https://PHONE_NAME capabilities
python3 tools/compute-client.py https://PHONE_NAME sha256
python3 tools/compute-client.py https://PHONE_NAME gpu-vector
```

Verify a request with a wrong token returns 401. Stop the service and confirm requests stop succeeding. Restart the service and repeat both jobs. Try cancelling a CPU job while running; GPU cancellation takes effect after the current GPU dispatch returns. Leave the phone locked for five minutes and repeat remote calls to discover One UI power restrictions. TailSocks Proxy mode must not take the Android VPN slot. The compute app itself uses a foreground service notification.

Physical S25 Ultra execution and remote TailSocks routing require validation on the user's phone; neither is established by compilation.
