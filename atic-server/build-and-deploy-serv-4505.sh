
mvn -Pdeploy -DskipTests=true package 

rsync target/atic.jar mschroeder@serv-4505.kl.dfki.de:/opt/kwen/atic-dfki-insight/atic-invex.jar
