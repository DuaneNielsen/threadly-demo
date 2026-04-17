# Plant PetClinic Divide-by-Zero Bug

Deploys PetClinic v1.1 (which contains the loyalty-score divide-by-zero bug) using the pre-built JAR and deploy script.

## Instructions

1. Run the deploy script to switch to v1.1:
   ```bash
   /home/duane/dx-do/demo/closed-loop/deploy.sh v1.1
   ```
   This kills any running PetClinic, copies the v1.1 JAR to active, starts it, and waits for health check.

2. Verify the bug is live:
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8180/owners/1
   ```
   Expect `500` on `/owners/1`. Report the result.
