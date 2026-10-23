package com.spacexjr.bmo.face

enum class BmoState {
    RESTING,
    LISTENING,
    THINKING,
    SPEAKING,
    HAPPY,
    SURPRISED,
    SAD,
    CONFUSED,
    ERROR,
    OFF;

    val label: String
        get() = when (this) {
            RESTING -> "Repouso"
            LISTENING -> "Escutando"
            THINKING -> "Pensando"
            SPEAKING -> "Falando"
            HAPPY -> "Feliz"
            SURPRISED -> "Surpreso"
            SAD -> "Triste"
            CONFUSED -> "Confuso"
            ERROR -> "Erro"
            OFF -> "Inativo"
        }
}
