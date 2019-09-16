#### Build ####
FROM    gradle:jdk11 as build

COPY    --chown=gradle:gradle ./* /home/gradle/tepid-server/
WORKDIR /home/gradle/tepid-server
RUN     gradle war

#### Tomcat ####
FROM    tomcat:8-jdk11
RUN     apt-get update
RUN     apt-get install -y samba-client ghostscript postgresql-client

COPY    server.xml conf/server.xml

COPY    --from=build /home/gradle/tepid-server/build/libs/tepid*.war /var/lib/tomcat8/webapps/tepid.war

CMD     ["catalina.sh", "run"]