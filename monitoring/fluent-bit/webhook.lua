function to_webhook_payload(tag, timestamp, record)
    local msg = record["log"] or record["message"] or ""
    local new_record = {
        alarm_name        = "Threadly Log Error",
        severity          = "Danger",
        status            = "OPEN",
        host              = os.getenv("DEMO_HOST") or "threadly-host",
        agent             = "fluent-bit|Threadly|Log Monitor",
        component_name    = "Threadly",
        message           = msg,
        alert_external_id = "fluentbit:threadly:log-error",
        metric_name       = "Log Events|ERROR:Rate",
        metric_value      = "1",
        caution_threshold = "5",
        danger_threshold  = "10",
        alarm_type        = "LOG_ALERT"
    }
    return 1, timestamp, new_record
end
