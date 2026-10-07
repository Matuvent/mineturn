execute store result score @s mineturn.ai run mineturn ai query ready mineturn:evoker_summon
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:evoker_summon
execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query distance
execute if score @s mineturn.ai matches ..3000 store result score @s mineturn.ai run mineturn ai query ready mineturn:fangs_circle
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:fangs_circle
return run function mineturn:ai/ranged_hold/turn {action:"mineturn:fangs_line",chase_distance:8000}
