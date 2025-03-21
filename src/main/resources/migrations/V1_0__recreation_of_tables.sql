create table meetups
(
    id           varchar(25)  not null,
    name         varchar(300) not null,
    homepage_url text,
    meetup_url   text,
    discord_url  text,
    linkedin_url text,
    kompot_url   text,
    ical_url     text,
    created_at   timestamptz default now(),
    updated_at   timestamptz default now()
);

create unique index meetups_id_uindex on meetups (id);

alter table meetups
    add constraint meetups_pk primary key (id);
