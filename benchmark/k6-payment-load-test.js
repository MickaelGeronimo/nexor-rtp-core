/**
 * Real load test for POST /api/v1/payments — replaces the "we tested it under load" claim in
 * the README with reproducible throughput and p95/p99 latency numbers from k6.
 *
 * Every virtual user generates its own random amount + unique Idempotency-Key per iteration, so
 * requests are NOT deduplicated by the idempotency layer — this measures the real per-payment
 * hot path (fraud screening -> ledger debit -> outbox insert), not the idempotency fast-path.
 *
 * ---------------------------------------------------------------------------
 * HOW TO RUN
 * ---------------------------------------------------------------------------
 *   1. Install k6:            https://k6.io/docs/get-started/installation/
 *   2. Start the stack:       docker compose up -d        (real Postgres + Kafka)
 *   3. Run the app:           mvn spring-boot:run
 *   4. Run the load test:
 *        k6 run benchmark/k6-payment-load-test.js
 *
 *      Override target, VUs, or duration without editing the file:
 *        k6 run -e BASE_URL=http://localhost:8080 --vus 50 --duration 30s benchmark/k6-payment-load-test.js
 *
 * k6 prints real p90/p95/p99 and throughput (req/s) in the summary at the end — that output is
 * the number to quote, not an estimate.
 * ---------------------------------------------------------------------------
 */
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'nexor-pay-key-dev'; // default dev key from application.yml

const paymentLatency = new Trend('payment_submit_duration', true);

export const options = {
    scenarios: {
        steady_load: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 20 },  // ramp-up
                { duration: '30s', target: 50 },  // steady state — the number worth quoting
                { duration: '10s', target: 0 },   // ramp-down
            ],
        },
    },
    thresholds: {
        // Fail the run (non-zero exit code) if these aren't met — turns "should be fast" into
        // an enforced, CI-checkable contract.
        http_req_duration: ['p(95)<300', 'p(99)<800'],
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const debtor = 'NEXOR:0001:1001-9';
    const creditor = 'NEXOR:0001:2002-8';
    const amount = (Math.random() * 500 + 1).toFixed(2);

    const payload = JSON.stringify({
        debtorAccount: debtor,
        creditorAccount: creditor,
        amount: amount,
        currency: 'BRL',
        remittanceInformation: 'k6 load test',
        rail: 'PIX',
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-API-Key': API_KEY,
            // Unique per iteration: this benchmarks the real payment path, not the
            // idempotency-cache replay path.
            'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
        },
    };

    const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);
    paymentLatency.add(res.timings.duration);

    check(res, {
        'status is 201 or 202': (r) => r.status === 201 || r.status === 202,
    });
}
