
    create table app_users (
        enabled bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        username varchar(50) not null,
        email varchar(120) not null,
        password varchar(255) not null,
        role enum ('ADMINISTRATOR','ORGANIZER','PARTICIPANT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table notifications (
        read_status bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        match_id bigint,
        recipient_id bigint not null,
        tournament_id bigint,
        message varchar(1000) not null,
        type enum ('MATCH_RESULT','MATCH_SCHEDULED','TOURNAMENT_COMPLETED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table participants (
        app_user_id bigint,
        group_id bigint,
        id bigint not null auto_increment,
        registered_at datetime(6),
        tournament_id bigint not null,
        name varchar(100) not null,
        name_lower varchar(100) not null,
        status enum ('ACTIVE','INACTIVE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table tournament_groups (
        id bigint not null auto_increment,
        tournament_id bigint not null,
        name varchar(50) not null,
        primary key (id)
    ) engine=InnoDB;

    create table tournament_matches (
        match_number integer not null,
        next_loser_slot integer,
        next_match_slot integer,
        round_number integer not null,
        score1 integer,
        score2 integer,
        group_id bigint,
        id bigint not null auto_increment,
        next_loser_match_id bigint,
        next_match_id bigint,
        participant1_id bigint,
        participant2_id bigint,
        scheduled_time datetime(6),
        tournament_id bigint not null,
        version bigint not null,
        winner_id bigint,
        bracket enum ('GRAND_FINAL','LOSERS','WINNERS'),
        status enum ('COMPLETED','PENDING','READY') not null,
        primary key (id),
        check ((score1 is null or score1 >= 0) and (score2 is null or score2 >= 0))
    ) engine=InnoDB;

    create table tournaments (
        end_date date not null,
        grand_final_reset bit not null,
        start_date date not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        organizer_id bigint not null,
        version bigint not null,
        name varchar(150) not null,
        description varchar(2000),
        format enum ('DOUBLE_ELIMINATION','ELIMINATION','GROUPS') not null,
        status enum ('COMPLETED','DRAFT','IN_PROGRESS','REGISTRATION') not null,
        primary key (id),
        check (end_date >= start_date)
    ) engine=InnoDB;

    alter table app_users 
       add constraint uk_user_username unique (username);

    alter table app_users 
       add constraint uk_user_email unique (email);

    create index idx_notification_recipient_read_created 
       on notifications (recipient_id, read_status, created_at);

    alter table participants 
       add constraint uk_participant_tournament_name unique (tournament_id, name_lower);

    create index idx_group_tournament 
       on tournament_groups (tournament_id);

    alter table tournament_groups 
       add constraint uk_group_name_per_tournament unique (tournament_id, name);

    create index idx_match_participant1 
       on tournament_matches (participant1_id);

    create index idx_match_participant2 
       on tournament_matches (participant2_id);

    alter table tournament_matches 
       add constraint uk_match_round_number unique (tournament_id, round_number, match_number);

    create index idx_tournament_start_date 
       on tournaments (start_date);

    create index idx_tournament_organizer 
       on tournaments (organizer_id);

    alter table notifications 
       add constraint FKig4ysg4mx2ofxaqveff0ory59 
       foreign key (match_id) 
       references tournament_matches (id);

    alter table notifications 
       add constraint FK7mpd9n24ptruj9hf4lw0otrf6 
       foreign key (recipient_id) 
       references app_users (id);

    alter table notifications 
       add constraint FKytbn5iaw43pmj2b5bah8tpi8 
       foreign key (tournament_id) 
       references tournaments (id);

    alter table participants 
       add constraint FKr677cxdyb31fg6dc57vsxkwqa 
       foreign key (app_user_id) 
       references app_users (id);

    alter table participants 
       add constraint FKbla8hyx0siv57qmsv28ah23ba 
       foreign key (group_id) 
       references tournament_groups (id);

    alter table participants 
       add constraint FKklwwmy0hy7uiy5caj9ngk650n 
       foreign key (tournament_id) 
       references tournaments (id);

    alter table tournament_groups 
       add constraint FK69cxdijppe91a9f11li2pbq4 
       foreign key (tournament_id) 
       references tournaments (id);

    alter table tournament_matches 
       add constraint FKsn2cq6rc1nrcettvndltpasdd 
       foreign key (group_id) 
       references tournament_groups (id);

    alter table tournament_matches 
       add constraint FKb4pb6c5fg0tunxfgvv5kxado5 
       foreign key (next_loser_match_id) 
       references tournament_matches (id);

    alter table tournament_matches 
       add constraint FKi4nqj1o5ladgi53g4ybrmxmoo 
       foreign key (next_match_id) 
       references tournament_matches (id);

    alter table tournament_matches 
       add constraint FKi9idhq9w2k07pk6e91675bqwm 
       foreign key (participant1_id) 
       references participants (id);

    alter table tournament_matches 
       add constraint FKg884w5iigxlh065fl2yrc5pcy 
       foreign key (participant2_id) 
       references participants (id);

    alter table tournament_matches 
       add constraint FKntwq3hn28yb2475ayat61cpdt 
       foreign key (tournament_id) 
       references tournaments (id);

    alter table tournament_matches 
       add constraint FK38c33tgmrt6acjj02cyriglm7 
       foreign key (winner_id) 
       references participants (id);

    alter table tournaments 
       add constraint FKblut3cm0sdalp0njpxvr2njhq 
       foreign key (organizer_id) 
       references app_users (id);
