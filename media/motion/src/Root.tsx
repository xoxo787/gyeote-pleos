import React from 'react';
import {Composition} from 'remotion';
import {Close, End, Film, FILM_LENGTH, Join, LEN, Play, Problem, Real, Rules, Title} from './scenes';
import {PASSER_LENGTH, PasserFilm, PEnd, PHandover, PLEN, PProblem, PReal, PReturn, PRules, PTitle, PTour} from './passer';

const size = {fps: 30, width: 1920, height: 1080};

/** `Film` is the whole video. The single scenes are registered too, for checking one at a time. */
export const Root: React.FC = () => (
  <>
    <Composition id="Film" component={Film} durationInFrames={FILM_LENGTH} {...size} />
    <Composition id="Title" component={Title} durationInFrames={LEN.title} {...size} />
    <Composition id="Problem" component={Problem} durationInFrames={LEN.problem} {...size} />
    <Composition id="Join" component={Join} durationInFrames={LEN.join} {...size} />
    <Composition id="Play" component={Play} durationInFrames={LEN.play} {...size} />
    <Composition id="Close" component={Close} durationInFrames={LEN.close} {...size} />
    <Composition id="Real" component={Real} durationInFrames={LEN.real} {...size} />
    <Composition id="Rules" component={Rules} durationInFrames={LEN.rules} {...size} />
    <Composition id="End" component={End} durationInFrames={LEN.end} {...size} />
    <Composition id="PasserFilm" component={PasserFilm} durationInFrames={PASSER_LENGTH} {...size} />
    <Composition id="PTitle" component={PTitle} durationInFrames={PLEN.title} {...size} />
    <Composition id="PProblem" component={PProblem} durationInFrames={PLEN.problem} {...size} />
    <Composition id="PHandover" component={PHandover} durationInFrames={PLEN.handover} {...size} />
    <Composition id="PTour" component={PTour} durationInFrames={PLEN.tour} {...size} />
    <Composition id="PReturn" component={PReturn} durationInFrames={PLEN.back} {...size} />
    <Composition id="PReal" component={PReal} durationInFrames={PLEN.real} {...size} />
    <Composition id="PRules" component={PRules} durationInFrames={PLEN.rules} {...size} />
    <Composition id="PEnd" component={PEnd} durationInFrames={PLEN.end} {...size} />
  </>
);
