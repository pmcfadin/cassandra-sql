FROM pmcfadin/cassandra-accord:latest

# Modify cassandra.yaml to use ByteOrderedPartitioner instead of Murmur3Partitioner
# ByteOrderedPartitioner requires num_tokens: 1 and doesn't support automatic token allocation
# Accord is already enabled in the base image
USER root
RUN sed -i 's/partitioner: org.apache.cassandra.dht.Murmur3Partitioner/partitioner: org.apache.cassandra.dht.ByteOrderedPartitioner/' /opt/cassandra/conf/cassandra.yaml && \
    sed -i 's/num_tokens: 16/num_tokens: 1/' /opt/cassandra/conf/cassandra.yaml && \
    sed -i 's/# initial_token:/initial_token: 0/' /opt/cassandra/conf/cassandra.yaml && \
    sed -i 's/^rpc_address: localhost/rpc_address: 0.0.0.0/' /opt/cassandra/conf/cassandra.yaml && \
    sed -i 's/auto_bootstrap: true/auto_bootstrap: false/' /opt/cassandra/conf/cassandra.yaml && \
    echo "broadcast_rpc_address: cassandra" >> /opt/cassandra/conf/cassandra.yaml

# Create a startup script that fixes volume permissions
RUN echo '#!/bin/bash\n\
# Ensure data directories exist and have correct permissions\n\
mkdir -p /opt/cassandra/data\n\
mkdir -p /opt/cassandra/data/accord_journal\n\
chown -R cassandra-user:cassandra-user /opt/cassandra/data\n\
# Start Cassandra as cassandra-user\n\
exec gosu cassandra-user /opt/cassandra/bin/cassandra -f\n\
' > /docker-entrypoint.sh && chmod +x /docker-entrypoint.sh

# Install gosu for step-down from root
RUN apt-get update && apt-get install -y gosu && rm -rf /var/lib/apt/lists/*

# Use our custom startup script
ENTRYPOINT ["/docker-entrypoint.sh"]
