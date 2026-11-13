from verify import main as verify_mqtt
from verify_tcp import main as verify_tcp
from verify_reuse_busy import main as verify_reuse_busy

verify_mqtt()
verify_tcp()
verify_reuse_busy()
