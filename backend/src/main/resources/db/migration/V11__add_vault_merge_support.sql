-- V11: Add merge_key_package to pairing_requests for bidirectional vault merge
ALTER TABLE pairing_requests
    ADD COLUMN merge_key_package VARCHAR(16000);
