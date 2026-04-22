function to_webhook_payload(tag, timestamp, record)
    local msg = record["log"] or record["message"] or ""
    local new_record = {
        alarm_name        = "PetClinic Log Error",
        severity          = "Danger",
        status            = "OPEN",
        host              = os.getenv("DEMO_HOST") or "petclinic-host",
        agent             = "fluent-bit|PetClinic|Log Monitor",
        component_name    = "PetClinic",
        message           = msg,
        alert_external_id = "fluentbit:petclinic:log-error",
        metric_name       = "Log Events|ERROR:Rate",
        metric_value      = "1",
        caution_threshold = "5",
        danger_threshold  = "10",
        alarm_type        = "LOG_ALERT"
    }
    return 1, timestamp, new_record
end
