alter table ${schema}.filter_rule
    add column active boolean not null default true;
