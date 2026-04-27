# Plant Threadly Divide-by-Zero Bug

Deploys Threadly v1.1 (which contains the discount-calculator divide-by-zero bug) using the pre-built JAR and deploy script.

## Instructions

1. Run the deploy script to switch to v1.1:
   ```bash
   ./deploy.sh v1.1
   ```
   This kills any running Threadly, copies the v1.1 JAR to active, starts it, and waits for health check.

2. Verify the bug is live:
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8180/products/7
   ```
   Expect `500` on `/products/7` (the "I'm a Teapot" promo item with `original_price = 0.00` triggers `ArithmeticException: / by zero`). Report the result.
