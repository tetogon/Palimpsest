#![no_main]

libfuzzer_sys::fuzz_target!(|data: &[u8]| palimpsest_core::fuzzing::attachment(data));
