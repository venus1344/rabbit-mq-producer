# rabbit-mq-producer
Just a sample app getting metrics from my PC and publishes to rabbitMq with the data being encrypted in transit

## How to run

1. Clone the repo
2. Create a config.properties file in the src/main/resources folder with the following keys:    
    - `rabbitmq.uri`
    - `encryption.key`
    - `secret.key`
3. Run `mvn clean package`
4. Run `java -jar target/rabbit-mq-producer-1.0-SNAPSHOT.jar`

